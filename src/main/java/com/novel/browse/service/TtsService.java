package com.novel.browse.service;

import com.novel.browse.config.TtsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Read aloud: synthesizes one short piece of text into MP3.
 *
 * <p>Synthesis runs through the locally installed edge-tts CLI, therefore every network detail stays
 * inside that package. Results are cached on disk keyed by voice + rate + pitch + text, so re-reading
 * the same paragraph costs nothing and repeated reads of popular books hit upstream only once.
 */
@Service
public class TtsService {

    private static final Pattern VOICE_PATTERN = Pattern.compile("[a-zA-Z]{2}-[a-zA-Z]{2}-[A-Za-z0-9]+");
    private static final Pattern RATE_PATTERN = Pattern.compile("[+-]?\\d{1,3}%");
    private static final Pattern PITCH_PATTERN = Pattern.compile("[+-]?\\d{1,4}Hz");

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    private final TtsProperties props;
    private final Semaphore slots;
    private final Path cacheDir;

    public TtsService(TtsProperties props) {
        this.props = props;
        this.slots = new Semaphore(Math.max(1, props.getMaxConcurrent()));
        this.cacheDir = Path.of(props.getCacheDir()).toAbsolutePath().normalize();
    }

    /** MP3 bytes for {@code text}; empty text and oversized requests are rejected upfront */
    public byte[] synthesize(String text, String voice, String rate, String pitch) {
        if (!props.isEnabled()) {
            throw new BadRequestException("error.tts.disabled");
        }
        String line = text == null ? "" : text.strip();
        if (line.isEmpty()) {
            throw new BadRequestException("error.tts.emptyText");
        }
        if (line.length() > props.getMaxTextLength()) {
            throw new BadRequestException("error.tts.textTooLong", props.getMaxTextLength());
        }

        String safeVoice = voice == null || voice.isBlank() ? props.getDefaultVoice() : voice.trim();
        if (!VOICE_PATTERN.matcher(safeVoice).matches()) {
            throw new BadRequestException("error.tts.invalidVoice", safeVoice);
        }
        List<String> allowed = props.getVoices();
        if (allowed != null && !allowed.isEmpty() && !allowed.contains(safeVoice)) {
            throw new BadRequestException("error.tts.voiceNotAllowed", safeVoice);
        }
        String safeRate = normalize(rate, "+0%", RATE_PATTERN);
        String safePitch = normalize(pitch, "+0Hz", PITCH_PATTERN);

        Path cached = cacheDir.resolve(fingerprint(safeVoice, safeRate, safePitch, line) + ".mp3");
        if (Files.isRegularFile(cached)) {
            return read(cached);
        }

        if (!slots.tryAcquire()) {
            throw new BadRequestException("error.tts.busy");
        }
        try {
            Files.createDirectories(cacheDir);
            Path tmp = Files.createTempFile(cacheDir, "synth-", ".mp3");
            try {
                String failure = run(safeVoice, safeRate, safePitch, line, tmp);
                if (failure != null) {
                    throw new BadRequestException("error.tts.failed", failure);
                }
                Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
            trimCache();
            return read(cached);
        } catch (IOException e) {
            throw new IllegalStateException("tts storage failure: " + e.getMessage(), e);
        } finally {
            slots.release();
        }
    }

    /** Invokes {@code python3 -m edge_tts …}; returns {@code null} on success or a short failure detail */
    private String run(String voice, String rate, String pitch, String text, Path target) {
        Path logFile = target.resolveSibling(target.getFileName() + ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(props.getPython(), "-m", "edge_tts",
                    "--voice", voice, "--rate", rate, "--pitch", pitch, "--text", text,
                    "--write-media", target.toString());
            builder.redirectErrorStream(true);
            builder.redirectOutput(logFile.toFile());
            Process process = builder.start();
            if (!process.waitFor(Math.max(5, props.getTimeoutSeconds()), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "timeout after " + props.getTimeoutSeconds() + "s";
            }
            if (process.exitValue() != 0 || !Files.exists(target) || Files.size(target) == 0) {
                String tail = tail(logFile);
                log.warn("edge-tts failed for voice {}: exit={} {}", voice, process.exitValue(), tail);
                return tail.isBlank() ? "exit=" + process.exitValue() : tail;
            }
            return null;
        } catch (IOException e) {
            // python missing or edge-tts not installed: say exactly that, the operator has to install it
            String hint = e.getMessage().toLowerCase(Locale.ROOT).contains("permission denied")
                    ? "cannot execute " + props.getPython()
                    : "cannot run " + props.getPython() + " -m edge_tts: " + e.getMessage();
            log.warn("edge-tts unavailable: {}", hint);
            return hint;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } finally {
            try {
                Files.deleteIfExists(logFile);
            } catch (IOException ignored) {
                // nothing else to do while cleaning the side car log
            }
        }
    }

    private String normalize(String value, String fallback, Pattern pattern) {
        String candidate = value == null || value.isBlank() ? fallback : value.trim();
        if (!pattern.matcher(candidate).matches()) {
            throw new BadRequestException("error.tts.invalidProsody", candidate);
        }
        // +0% stays as is, otherwise make sure a sign is present
        if (candidate.startsWith("+") || candidate.startsWith("-")) {
            return candidate;
        }
        return "+" + candidate;
    }

    private String tail(Path file) {
        try (Stream<String> lines = Files.exists(file) ? Files.lines(file, StandardCharsets.UTF_8) : Stream.empty()) {
            List<String> all = lines.toList();
            String last = all.isEmpty() ? "" : all.get(all.size() - 1);
            return last.length() > 200 ? last.substring(0, 200) : last;
        } catch (IOException e) {
            return "";
        }
    }

    private byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read synthesized audio: " + e.getMessage(), e);
        }
    }

    /** Drops the oldest cached snippets once the configured count is exceeded */
    private void trimCache() {
        try (Stream<Path> files = Files.list(cacheDir)) {
            List<Path> mp3 = files
                    .filter(p -> p.getFileName().toString().endsWith(".mp3"))
                    .sorted(Comparator.comparingLong(this::modified))
                    .toList();
            int excess = mp3.size() - props.getMaxCacheFiles();
            for (int i = 0; i < excess; i += 1) {
                Files.deleteIfExists(mp3.get(i));
            }
        } catch (IOException e) {
            // cache hygiene is best effort
        }
    }

    private long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private String fingerprint(String voice, String rate, String pitch, String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((voice + "|" + rate + "|" + pitch + "|" + text).getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder();
            for (byte b : digest.digest()) {
                key.append(String.format("%02x", b));
            }
            return key.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
