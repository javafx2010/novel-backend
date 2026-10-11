package com.novel.browse.data;

import com.novel.browse.domain.Category;
import com.novel.browse.repository.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round trip of the database snapshot: export the database, wipe it, load it back */
@SpringBootTest(properties = {
        "rocksdb.cache.path=build/tmp/db-snapshot-test/cache",
        "app.db.snapshot.file=build/tmp/db-snapshot-test.sql",
        "app.upload.dir=build/tmp/db-snapshot-test/epub",
        "app.cover.dir=build/tmp/db-snapshot-test/covers"
})
class DbSnapshotTest {

    private static final Path FILE = Path.of("build/tmp/db-snapshot-test.sql");

    @Autowired
    private DbSnapshot snapshot;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void exportAndRestoreRoundTrip() throws Exception {
        Files.deleteIfExists(FILE);
        categoryRepository.save(new Category("快照分类", "created by the test", 7));

        snapshot.export();

        assertTrue(Files.exists(FILE), "snapshot file should be written");
        assertTrue(Files.size(FILE) > 0, "snapshot file should not be empty");

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
