package com.novel.browse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Read aloud settings, prefix {@code app.tts}.
 *
 * <p>The synthesis itself is done by edge-tts installed on the same machine ({@code pip install edge-tts});
 * this service only shells out to its CLI and caches the produced MP3:
 *
 * <pre>
 *   python3 -m edge_tts --voice zh-CN-XiaoxiaoNeural --rate +0% --pitch +0Hz \
 *           --text "…" --write-media /tmp/xxx.mp3
 * </pre>
 *
 * Keeping the protocol (Sec-MS-GEC token, WebSocket frames) inside edge-tts means an upstream change is
 * fixed by upgrading the python package instead of this codebase.
 */
@Component
@ConfigurationProperties(prefix = "app.tts")
public class TtsProperties {

    /** Master switch: false removes the /tts endpoint entirely */
    private boolean enabled = true;

    /** Python interpreter owning edge-tts; use the venv interpreter when it was installed into one */
    private String python = "python3";

    /** Where synthesized MP3 snippets are cached, keyed by voice + rate + pitch + text */
    private String cacheDir = "./data/tts";

    /** Hard limit for one CLI call; the frontend sends a single paragraph per request */
    private int timeoutSeconds = 30;

    /** Longer text is rejected instead of being forwarded upstream */
    private int maxTextLength = 1200;

    /** Concurrent CLI calls; extra requests are rejected to protect the service */
    private int maxConcurrent = 3;

    /** Cache files kept on disk; the oldest are dropped past the limit */
    private int maxCacheFiles = 3000;

    /** Voice used when the request omits one */
    private String defaultVoice = "zh-CN-XiaoxiaoNeural";

    /** Allowed voices; empty means every {@code xx-YY-*Neural} name is accepted */
    private List<String> voices = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPython() {
        return python;
    }

    public void setPython(String python) {
        this.python = python;
    }

    public String getCacheDir() {
        return cacheDir;
    }

    public void setCacheDir(String cacheDir) {
        this.cacheDir = cacheDir;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxTextLength() {
        return maxTextLength;
    }

    public void setMaxTextLength(int maxTextLength) {
        this.maxTextLength = maxTextLength;
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    public int getMaxCacheFiles() {
        return maxCacheFiles;
    }

    public void setMaxCacheFiles(int maxCacheFiles) {
        this.maxCacheFiles = maxCacheFiles;
    }

    public String getDefaultVoice() {
        return defaultVoice;
    }

    public void setDefaultVoice(String defaultVoice) {
        this.defaultVoice = defaultVoice;
    }

    public List<String> getVoices() {
        return voices;
    }

    public void setVoices(List<String> voices) {
        this.voices = voices;
    }
}
