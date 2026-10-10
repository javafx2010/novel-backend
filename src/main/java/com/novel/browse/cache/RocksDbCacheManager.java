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
 * RocksDB cache manager: all caches share one RocksDB instance (isolated by key prefix),
 * caches are created on demand and native resources are released on shutdown.
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
            throw new IllegalStateException("Cannot create the RocksDB data directory: " + dir.getAbsolutePath());
        }
        this.options = new Options()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)
                .setCompressionType(CompressionType.LZ4_COMPRESSION)
                .setWriteBufferSize(properties.getWriteBufferSizeMb() * 1024L * 1024L);
        try {
            this.db = RocksDB.open(options, dir.getAbsolutePath());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to open RocksDB: " + dir.getAbsolutePath(), e);
        }

        loadCaches(properties.getNames() == null ? List.of() : properties.getNames());
        log.info("RocksDB cache enabled: path={}, ttl={}ms, caches={}", dir.getAbsolutePath(), ttlMillis, caches.size());
    }

    /** Caches declared in the pre-create configuration */
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

    /** Cache names not declared up front are created on demand */
    private Cache getMissingCache(String name) {
        return caches.computeIfAbsent(name, n -> new RocksDbCache(n, db, ttlMillis));
    }

    /** Clears the whole RocksDB instance (used at startup to match the re-seeded in-memory database) */
    private void purgeAll() {
        try (RocksIterator iterator = db.newIterator()) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                db.delete(iterator.key());
            }
        } catch (Exception e) {
            log.error("Failed to clear the RocksDB cache", e);
        }
    }

    /** Statistics of every cache */
    public List<RocksDbCache.CacheStats> stats() {
        List<RocksDbCache.CacheStats> stats = new ArrayList<>();
        for (Cache cache : caches.values()) {
            if (cache instanceof RocksDbCache rocksCache) {
                stats.add(rocksCache.stats());
            }
        }
        return stats;
    }

    /** Cache overview, including the RocksDB instance path */
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
            log.warn("Failed to sync the RocksDB WAL", e);
        }
        db.close();
        options.close();
        log.info("RocksDB cache disabled");
    }

    /** Key prefix, kept for troubleshooting and external tools */
    static byte[] prefixOf(String name) {
        return (name + ":").getBytes(StandardCharsets.UTF_8);
    }
}
