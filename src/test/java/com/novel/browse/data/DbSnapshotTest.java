package com.novel.browse.data;

import com.novel.browse.domain.Category;
import com.novel.browse.repository.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round trip of the database snapshot: export the database, wipe it, load it back */
@SpringBootTest
class DbSnapshotTest {

    /** Fresh directory per run: a snapshot left over from an earlier run would be restored at context startup */
    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void snapshotProperties(DynamicPropertyRegistry registry) {
        registry.add("app.db.snapshot.file", () -> dir.resolve("db-snapshot.sql").toString());
        registry.add("app.upload.dir", () -> dir.resolve("epub").toString());
        registry.add("app.cover.dir", () -> dir.resolve("covers").toString());
        registry.add("rocksdb.cache.path", () -> dir.resolve("cache").toString());
    }

    @Autowired
    private DbSnapshot snapshot;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void exportAndRestoreRoundTrip() throws Exception {
        Path file = dir.resolve("db-snapshot.sql");
        Files.deleteIfExists(file);
        categoryRepository.save(new Category("快照分类", "created by the test", 7));

        snapshot.export();

        assertTrue(Files.exists(file), "snapshot file should be written");
        assertTrue(Files.size(file) > 0, "snapshot file should not be empty");

        // Wipe everything, then load the snapshot back
        jdbc.execute("delete from nb_chapter");
        jdbc.execute("delete from nb_novel");
        jdbc.execute("delete from nb_category");
        assertEquals(0, categoryRepository.count());

        snapshot.restore();

        assertEquals(1, categoryRepository.count());
        Category restored = categoryRepository.findAll().getFirst();
        assertEquals("快照分类", restored.getName());
        assertEquals("created by the test", restored.getDescription());
        assertEquals(7, restored.getSortNo());
    }
}
