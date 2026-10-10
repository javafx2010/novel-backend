package com.novel.browse.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * RocksDB 缓存配置，前缀 {@code rocksdb.cache}。
 */
@ConfigurationProperties(prefix = "rocksdb.cache")
public class RocksDbCacheProperties {

    /** 是否启用 RocksDB 缓存 */
    private boolean enabled = true;

    /** 数据目录 */
    private String path = "./.rocksdb-cache";

    /** 统一过期时间，0 表示永不过期 */
    private Duration ttl = Duration.ofMinutes(10);

    /** 启动时是否清空缓存（内存库重启会重新种子，故默认清空） */
    private boolean cleanOnStart = true;

    /** 需要预建的缓存名称 */
    private List<String> names = new ArrayList<>();

    /** 写缓冲大小，单位 MB */
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
