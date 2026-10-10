package com.novel.browse.web;

import com.novel.browse.cache.RocksDbCacheManager;
import org.springframework.cache.CacheManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RocksDB cache operations: statistics and cache clearing.
 */
@RestController
@RequestMapping("/cache")
public class CacheAdminController {

    private final CacheManager cacheManager;

    public CacheAdminController(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /** Cache overview and per-cache statistics */
    @GetMapping
    public Map<String, Object> stats() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", cacheManager.getClass().getName());
        body.put("cacheNames", cacheManager.getCacheNames());
        if (cacheManager instanceof RocksDbCacheManager rocksDb) {
            body.putAll(rocksDb.info());
        }
        return body;
    }

    /** Clears the given cache */
    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Object>> clear(@PathVariable String name) {
        var cache = cacheManager.getCache(name);
        if (cache == null) {
            return ResponseEntity.notFound().build();
        }
        cache.clear();
        return ResponseEntity.ok(Map.of("name", name, "cleared", true));
    }
}
