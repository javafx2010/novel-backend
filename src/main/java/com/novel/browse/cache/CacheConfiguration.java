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
 * 缓存配置：默认注册 RocksDB 作为缓存实现。
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(RocksDbCacheProperties.class)
public class CacheConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CacheConfiguration.class);

    @Bean
    public CacheManager cacheManager(RocksDbCacheProperties properties) {
        if (!properties.isEnabled()) {
            log.warn("RocksDB 缓存已关闭（rocksdb.cache.enabled=false），将使用无操作 CacheManager");
            return new NoOpCacheManager();
        }
        return new RocksDbCacheManager(properties);
    }
}
