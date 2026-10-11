package com.novel.browse.data;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Keeps the in-memory database across restarts: the whole database is exported to a SQL file when the
 * application context closes (SIGTERM / graceful shutdown) and loaded back at the next startup.
 * <p>
 * Uses the H2 commands {@code SCRIPT TO} and {@code RUNSCRIPT FROM}; any other database is skipped with a
 * warning (it already persists on its own). The restore runs before Hibernate updates the schema
 * (see {@code DbSnapshotOrder}), so new columns of an upgrade are added to the restored tables.
 */
@Component(DbSnapshot.BEAN_NAME)
public class DbSnapshot implements ApplicationListener<ContextClosedEvent> {

    /** Bean name: {@code DbSnapshotOrder} makes the EntityManagerFactory depend on it */
    public static final String BEAN_NAME = "dbSnapshot";

    private static final Logger log = LoggerFactory.getLogger(DbSnapshot.class);

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final boolean enabled;
    private final String file;

    public DbSnapshot(JdbcTemplate jdbc,
                      DataSource dataSource,
                      @Value("${app.db.snapshot.enabled:true}") boolean enabled,
                      @Value("${app.db.snapshot.file:./data/db-snapshot.sql}") String file) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.enabled = enabled;
        this.file = file;
    }

    /** Loads the snapshot of the previous run; runs before Hibernate touches the schema */
    @PostConstruct
    public void restore() {
        if (!enabled) {
            log.info("Database snapshot disabled (app.db.snapshot.enabled=false): the database starts empty");
            return;
        }
        if (!isH2()) {
            return;
        }
        Path path = snapshotPath();
        if (!Files.isRegularFile(path)) {
            log.info("No database snapshot at {}, starting with an empty database", path);
            return;
        }
        try {
            // Fresh in-memory database, and also makes the restore work if it ever runs after Hibernate
            jdbc.execute("DROP ALL OBJECTS");
            jdbc.execute("RUNSCRIPT FROM '" + literal(path.toString()) + "' CHARSET 'UTF-8'");
            log.info("Database restored from snapshot: {} ({} tables, {} KB)", path, countTables(), sizeKb(path));
        } catch (Exception e) {
            log.error("Failed to restore the database snapshot {} ({}): starting with an empty database",
                    path, e.getMessage());
        }
    }

    /** Writes the current database to the snapshot file (atomic replace) */
    public void export() {
        if (!enabled) {
            return;
        }
        if (!isH2()) {
            return;
        }
        Path path = snapshotPath();
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
            jdbc.execute("SCRIPT TO '" + literal(tmp.toAbsolutePath().toString()) + "' CHARSET 'UTF-8'");
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            log.info("Database exported to snapshot: {} ({} KB)", path, sizeKb(path));
        } catch (Exception e) {
            log.error("Failed to export the database to {} ({})", path, e.getMessage());
        }
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        export();
    }

    /** Snapshot commands are H2 specific: other databases keep their data on their own */
    private boolean isH2() {
        String product;
        try (Connection connection = dataSource.getConnection()) {
            product = connection.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            log.warn("Cannot read the database metadata, skipping the snapshot: {}", e.getMessage());
            return false;
        }
        if (product != null && product.toLowerCase().startsWith("h2")) {
            return true;
        }
        log.warn("Database snapshot supports H2 only (found {}), skipped: data is kept by the database itself", product);
        return false;
    }

    private Path snapshotPath() {
        return Path.of(file).toAbsolutePath().normalize();
    }

    private int countTables() {
        try {
            Integer count = jdbc.queryForObject(
                    "select count(*) from information_schema.tables where upper(table_schema) = 'PUBLIC'", Integer.class);
            return count == null ? 0 : count;
        } catch (Exception e) {
            return -1;
        }
    }

    private long sizeKb(Path path) {
        try {
            return Files.size(path) / 1024;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Escapes a path so it can be used as an SQL string literal */
    private String literal(String value) {
        return value.replace("'", "''");
    }
}
