package com.novel.browse.cache;

import org.rocksdb.CompressionType;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RocksDB 缓存管理器：所有缓存共享一个 RocksDB 实例（按 key 前缀隔离），
 * 不存在则会按需创建，应用关闭时释放 native 资源。
 */
public class RocksDbCacheManager implements CacheManager, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RocksDbCacheManager.class);

    private final Map<String, Cache> caches = new ConcurrentHashMap<>();
    private final RocksDB db;
    private final Options options;
    private final long ttlMillis;
    private final boolean cleanOnStart;

    public RocksDbCacheManager(RocksDbCacheProperties properties) {
        this.ttlMillis = properties.getTtl() == null ? 0L : properties.getTtl().toMillis();
        this.cleanOnStart = properties.isCleanOnStart();

        RocksDB.loadLibrary();
        File dir = new File(properties.getPath());
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IllegalStateException("无法创建 RocksDB 数据目录：" + dir.getAbsolutePath());
        }
        this.options = new Options()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)
                .setCompressionType(CompressionType.LZ4_COMPRESSION)
                .setWriteBufferSize(properties.getWriteBufferSizeMb() * 1024L * 1024L);
        try {
            this.db = RocksDB.open(options, dir.getAbsolutePath());
        } catch (Exception e) {
            throw new IllegalStateException("打开 RocksDB 失败：" + dir.getAbsolutePath(), e);
        }

        loadCaches(properties.getNames() == null ? List.of() : properties.getNames());
        log.info("RocksDB 缓存已启用：path={}，ttl={}ms，缓存数={}", dir.getAbsolutePath(), ttlMillis, caches.size());
    }

    /** 预建配置中声明的缓存 */
    private void loadCaches(Collection<String> names) {
        if (cleanOnStart) {
            purgeAll();
        }
        for (String name : names) {
            caches.put(name, new RocksDbCache(name, db, ttlMillis));
        }
    }

    @Override
    public Cache getCache(String name) {
        Cache cache = caches.get(name);
        if (cache == null) {
            cache = getMissingCache(name);
        }
        return cache;
    }

    @Override
    public Collection<String> getCacheNames() {
        return Collections.unmodifiableSet(new java.util.TreeSet<>(caches.keySet()));
    }

    /** 未预先声明的缓存名按需创建 */
    private Cache getMissingCache(String name) {
        return caches.computeIfAbsent(name, n -> new RocksDbCache(n, db, ttlMillis));
    }

    /** 清空整个 RocksDB 实例（启动时用于对齐内存库重新种子的数据） */
    private void purgeAll() {
        try (RocksIterator iterator = db.newIterator()) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                db.delete(iterator.key());
            }
        } catch (Exception e) {
            log.error("清空 RocksDB 缓存失败", e);
        }
    }

    /** 各缓存的统计信息 */
    public List<RocksDbCache.CacheStats> stats() {
        List<RocksDbCache.CacheStats> stats = new ArrayList<>();
        for (Cache cache : caches.values()) {
            if (cache instanceof RocksDbCache rocksCache) {
                stats.add(rocksCache.stats());
            }
        }
        return stats;
    }

    /** 缓存概览，包含 RocksDB 实例路径等 */
    public Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("ttlMillis", ttlMillis);
        info.put("cleanOnStart", cleanOnStart);
        info.put("caches", stats());
        return info;
    }

    @Override
    public void destroy() {
        caches.values().stream()
                .filter(c -> c instanceof RocksDbCache)
                .forEach(c -> ((RocksDbCache) c).shutdown());
        caches.clear();
        try {
            db.syncWal();
        } catch (Exception e) {
            log.warn("RocksDB WAL 同步失败", e);
        }
        db.close();
        options.close();
        log.info("RocksDB 缓存已关闭");
    }

    /** key 前缀，便于排查；保留给外部工具使用 */
    static byte[] prefixOf(String name) {
        return (name + ":").getBytes(StandardCharsets.UTF_8);
    }
}
