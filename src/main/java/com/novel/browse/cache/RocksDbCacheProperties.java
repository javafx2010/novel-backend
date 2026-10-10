package com.novel.browse.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * RocksDB cache settings, prefix {@code rocksdb.cache}.
 */
@ConfigurationProperties(prefix = "rocksdb.cache")
public class RocksDbCacheProperties {

    /** Whether the RocksDB cache is enabled */
    private boolean enabled = true;

    /** Data directory */
    private String path = "./.rocksdb-cache";

    /** Unified TTL, 0 means never expire */
    private Duration ttl = Duration.ofMinutes(10);

    /** Whether to clear the cache on startup (the in-memory database is re-seeded on restart, so it defaults to true) */
    private boolean cleanOnStart = true;

    /** Cache names to create up front */
    private List<String> names = new ArrayList<>();

    /** Write buffer size in MB */
    private int writeBufferSizeMb = 8;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public boolean isCleanOnStart() {
        return cleanOnStart;
    }

    public void setCleanOnStart(boolean cleanOnStart) {
        this.cleanOnStart = cleanOnStart;
    }

    public List<String> getNames() {
        return names;
    }

    public void setNames(List<String> names) {
        this.names = names;
    }

    public int getWriteBufferSizeMb() {
        return writeBufferSizeMb;
    }

    public void setWriteBufferSizeMb(int writeBufferSizeMb) {
        this.writeBufferSizeMb = writeBufferSizeMb;
    }
}
