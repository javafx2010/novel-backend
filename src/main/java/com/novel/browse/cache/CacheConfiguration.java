package com.novel.browse.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cache configuration: registers RocksDB as the default cache implementation.
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(RocksDbCacheProperties.class)
public class CacheConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CacheConfiguration.class);

    @Bean
    public CacheManager cacheManager(RocksDbCacheProperties properties) {
        if (!properties.isEnabled()) {
            log.warn("RocksDB cache is disabled (rocksdb.cache.enabled=false), falling back to a no-op CacheManager");
            return new NoOpCacheManager();
        }
        return new RocksDbCacheManager(properties);
    }
}
