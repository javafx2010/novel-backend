package com.novel.browse.cache;

import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.Cache.ValueRetrievalException;
import org.springframework.cache.support.SimpleValueWrapper;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link Cache} implementation backed by RocksDB.
 * <p>
 * Keys inside one RocksDB instance are prefixed with {@code cacheName:}, entries are written with JDK serialization,
 * and expire by TTL on read. Expired or corrupt entries count as a miss: availability wins over caching.
 */
public class RocksDbCache implements Cache {

    private static final Logger log = LoggerFactory.getLogger(RocksDbCache.class);

    private final String name;
    private final RocksDB db;
    private final String keyPrefix;
    private final long ttlMillis;
    private final WriteOptions writeOptions;
    private final ReadOptions readOptions;

    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private final AtomicLong putCount = new AtomicLong();
    private final AtomicLong evictCount = new AtomicLong();

    public RocksDbCache(String name, RocksDB db, long ttlMillis) {
        this.name = name;
        this.db = db;
        this.keyPrefix = name + ":";
        this.ttlMillis = ttlMillis;
        this.writeOptions = new WriteOptions().setSync(false);
        this.readOptions = new ReadOptions();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public RocksDB getNativeCache() {
        return db;
    }

    @Override
    public ValueWrapper get(Object key) {
        Object value = lookup(key);
        return value == null ? null : new SimpleValueWrapper(value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        Object cached = lookup(key);
        if (cached != null) {
            return (T) cached;
        }
        try {
            T value = valueLoader.call();
            put(key, value);
            return value;
        } catch (Exception ex) {
            throw new ValueRetrievalException(key, valueLoader, ex);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
        Object value = lookup(key);
        if (value == null) {
            return null;
        }
        if (type != null && !type.isInstance(value)) {
            log.warn("Cache[{}] type mismatch: expected {}, actual {}, handled as a miss", name, type.getName(), value.getClass().getName());
            evict(key);
            return null;
        }
        return (T) value;
    }

    @Override
    public void put(Object key, Object value) {
        if (value == null) {
            return;
        }
        try {
            byte[] bytes = JdkCacheSerializer.serialize(new CacheEntry(System.currentTimeMillis(), value));
            db.put(writeOptions, keyBytes(key), bytes);
            putCount.incrementAndGet();
        } catch (IOException e) {
            // Objects that cannot be serialized are simply not cached, the business flow is unaffected
            log.warn("Cache[{}] write failed, skipped: key={}, value={}", name, key, value.getClass().getName(), e);
        } catch (Exception e) {
            log.error("Cache[{}] write error: key={}", name, key, e);
        }
    }

    @Override
    public void evict(Object key) {
        try {
            db.delete(writeOptions, keyBytes(key));
            evictCount.incrementAndGet();
        } catch (Exception e) {
            log.error("Cache[{}] delete error: key={}", name, key, e);
        }
    }

    @Override
    public void clear() {
        try (RocksIterator iterator = db.newIterator()) {
            for (iterator.seek(keyPrefix.getBytes(StandardCharsets.UTF_8)); iterator.isValid(); iterator.next()) {
                String key = new String(iterator.key(), StandardCharsets.UTF_8);
                if (!key.startsWith(keyPrefix)) {
                    break;
                }
                db.delete(writeOptions, iterator.key());
            }
        } catch (Exception e) {
            log.error("Cache[{}] clear error", name, e);
        }
    }

    /** Hit / miss / write / delete counters */
    public CacheStats stats() {
        return new CacheStats(name, hitCount.get(), missCount.get(), putCount.get(), evictCount.get(), size());
    }

    /** Number of entries that are not expired */
    public int size() {
        int size = 0;
        try (RocksIterator iterator = db.newIterator(readOptions)) {
            for (iterator.seek(keyPrefix.getBytes(StandardCharsets.UTF_8)); iterator.isValid(); iterator.next()) {
                String key = new String(iterator.key(), StandardCharsets.UTF_8);
                if (!key.startsWith(keyPrefix)) {
                    break;
                }
                if (readEntry(iterator.value()) != null) {
                    size++;
                }
            }
        }
        return size;
    }

    void shutdown() {
        writeOptions.close();
        readOptions.close();
    }

    private Object lookup(Object key) {
        byte[] bytes;
        try {
            bytes = db.get(readOptions, keyBytes(key));
        } catch (Exception e) {
            log.error("Cache[{}] read error: key={}", name, key, e);
            missCount.incrementAndGet();
            return null;
        }
        if (bytes == null) {
            missCount.incrementAndGet();
            log.debug("Cache[{}] miss: key={}", name, key);
            return null;
        }
        CacheEntry entry = readEntry(bytes);
        if (entry == null || entry.value == null) {
            missCount.incrementAndGet();
            if (entry != null && entry.value == null) {
                evict(key);
            }
            return null;
        }
        if (isExpired(entry)) {
            missCount.incrementAndGet();
            evict(key);
            log.debug("Cache[{}] expired: key={}", name, key);
            return null;
        }
        hitCount.incrementAndGet();
        log.debug("Cache[{}] hit: key={}", name, key);
        return entry.value;
    }

    private CacheEntry readEntry(byte[] bytes) {
        try {
            Object obj = JdkCacheSerializer.deserialize(bytes);
            return obj instanceof CacheEntry entry ? entry : null;
        } catch (Exception e) {
            log.warn("Cache[{}] deserialization failed, handled as a miss", name, e);
            return null;
        }
    }

    private boolean isExpired(CacheEntry entry) {
        return ttlMillis > 0 && System.currentTimeMillis() - entry.createdAt > ttlMillis;
    }

    private byte[] keyBytes(Object key) {
        return (keyPrefix + toKeyPart(key)).getBytes(StandardCharsets.UTF_8);
    }

    private String toKeyPart(Object key) {
        if (key == null) {
            return "null";
        }
        if (key instanceof String || key instanceof Number || key instanceof Boolean || key instanceof Character) {
            return String.valueOf(key);
        }
        try {
            return "S:" + Base64.getUrlEncoder().encodeToString(JdkCacheSerializer.serialize(key));
        } catch (IOException e) {
            throw JdkCacheSerializer.wrap("Failed to serialize the cache key", e);
        }
    }

    /** Cache entry, carries the write time used for TTL evaluation */
    private record CacheEntry(long createdAt, Object value) implements Serializable {
    }

    /** Simple statistics */
    public record CacheStats(String name, long hits, long misses, long puts, long evictions, int size) {

        public double hitRate() {
            long total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total;
        }
    }
}
