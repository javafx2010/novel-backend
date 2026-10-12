package com.novel.browse.web;

import com.novel.browse.config.TtsProperties;
import com.novel.browse.service.TtsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * Read aloud endpoint {@code /tts}: returns MP3 audio for one short piece of text.
 *
 * <p>The reader sends one paragraph per request, plays it while the next one is being synthesized,
 * and highlights the speaking paragraph. Audio is cached here, so identical lines are synthesized once.
 */
@RestController
@ConditionalOnProperty(name = "app.tts.enabled", matchIfMissing = true)
public class TtsController {

    private final TtsService ttsService;
    private final TtsProperties props;

    public TtsController(TtsService ttsService, TtsProperties props) {
        this.ttsService = ttsService;
        this.props = props;
    }

    /** Preferred form: long or punctuation heavy text travels safely in the request body */
    @PostMapping(path = "/tts", produces = "audio/mpeg")
    public ResponseEntity<byte[]> speak(@RequestBody(required = false) SpeechRequest body) {
        SpeechRequest request = body == null ? new SpeechRequest(null, null, null, null) : body;
        return audio(ttsService.synthesize(request.text(), request.voice(), request.rate(), request.pitch()));
    }

    /** Convenience for debugging and for direct audio playback in a browser tab */
    @GetMapping(path = "/tts", produces = "audio/mpeg")
    public ResponseEntity<byte[]> speakGet(@RequestParam String text,
                                          @RequestParam(required = false) String voice,
                                          @RequestParam(required = false) String rate,
                                          @RequestParam(required = false) String pitch) {
        return audio(ttsService.synthesize(text, voice, rate, pitch));
    }

    /** The voices the site offers, mirrored here so the endpoint documents what it accepts */
    @GetMapping(path = "/tts/voices", produces = MediaType.APPLICATION_JSON_VALUE)
    public VoiceList voices() {
        return new VoiceList(props.getDefaultVoice(), props.getVoices());
    }

    private ResponseEntity<byte[]> audio(byte[] mp3) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.valueOf("audio/mpeg").toString())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(mp3.length))
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                .body(mp3);
    }

    /** Request payload of {@code POST /tts} */
    public record SpeechRequest(String text, String voice, String rate, String pitch) {
    }

    /** Default voice plus the whitelist configured for this deployment */
    public record VoiceList(String defaultVoice, java.util.List<String> voices) {
    }
}
