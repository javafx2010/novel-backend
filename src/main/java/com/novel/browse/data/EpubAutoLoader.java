package com.novel.browse.data;

import com.novel.browse.domain.Category;
import com.novel.browse.repository.CategoryRepository;
import com.novel.browse.repository.NovelRepository;
import com.novel.browse.service.AdminService;
import com.novel.browse.service.epub.EpubStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Startup scan: subdirectories of the upload directory map one-to-one to novel categories.
 * <ol>
 *   <li>Subdirectory to category: the directory name is the category name, created when missing (sortNo=999, listed last);</li>
 *   <li>Category to subdirectory: missing directories are created for existing categories so EPUB files can be dropped in;</li>
 *   <li>Files are imported with content-hash deduplication, already imported files are skipped, a single failure is logged as ERROR and the scan continues.</li>
 * </ol>
 * Runs after {@link DataInitializer} (larger Order value).
 */
@Component
@Order(100)
public class EpubAutoLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EpubAutoLoader.class);

    private final EpubStorage epubStorage;
    private final AdminService adminService;
    private final NovelRepository novelRepository;
    private final CategoryRepository categoryRepository;

    @Value("${app.upload.scan-on-start:true}")
    private boolean scanOnStart;

    /** EPUB files placed directly under the upload directory root belong to this category (created when missing) */
    @Value("${app.upload.default-category-name:Imported}")
    private String defaultCategoryName;

    @Value("${app.upload.default-status:FINISHED}")
    private String defaultStatus;

    /** Whether to create missing disk directories for existing categories */
    @Value("${app.upload.ensure-category-dirs:true}")
    private boolean ensureCategoryDirs;

    public EpubAutoLoader(EpubStorage epubStorage,
                          AdminService adminService,
                          NovelRepository novelRepository,
                          CategoryRepository categoryRepository) {
        this.epubStorage = epubStorage;
        this.adminService = adminService;
        this.novelRepository = novelRepository;
        this.categoryRepository = categoryRepository;
    }

    @Override
    public void run(String... args) {
        if (ensureCategoryDirs) {
            int created = ensureCategoryDirs();
            if (created > 0) {
                log.info("Created upload directories for {} categories ({})", created, epubStorage.dir());
            }
        }
        if (!scanOnStart) {
            log.info("Startup directory scan disabled (app.upload.scan-on-start=false): {}", epubStorage.dir());
            return;
        }
        List<EpubStorage.ScannedFile> files = epubStorage.scan();
        if (files.isEmpty()) {
            log.info("Startup directory scan: {} (no .epub file)", epubStorage.dir());
            return;
        }
        long start = System.currentTimeMillis();
        Map<String, Category> categories = new HashMap<>();
        int imported = 0;
        int skipped = 0;
        int failed = 0;
        for (EpubStorage.ScannedFile scanned : files) {
            String hash;
            try {
                hash = epubStorage.sha256(Files.readAllBytes(scanned.path()));
            } catch (Exception e) {
                failed++;
                log.error("Startup scan: failed to read file {} ({})", scanned.path().getFileName(), e.getMessage());
                continue;
            }
            if (novelRepository.existsBySourceHash(hash)) {
                skipped++;
                continue;
            }
            String categoryName = scanned.categoryName() == null ? defaultCategoryName : scanned.categoryName();
            Category category = categories.computeIfAbsent(categoryName, this::resolveCategory);
            try {
                adminService.importFile(scanned.path(), hash, category, defaultStatus);
                imported++;
            } catch (Exception e) {
                failed++;
                log.error("Startup scan: failed to import {}/{} ({})", categoryName, scanned.path().getFileName(), e.getMessage());
            }
        }
        log.info("Startup directory scan finished: {} - {} file(s), {} imported, {} already present, {} failed, {} ms",
                epubStorage.dir(), files.size(), imported, skipped, failed, System.currentTimeMillis() - start);
    }

    /** Creates missing directories for existing categories, returns how many were created */
    private int ensureCategoryDirs() {
        int created = 0;
        for (Category category : categoryRepository.findAllByOrderBySortNoAsc()) {
            if (epubStorage.ensureCategoryDir(category.getName())) {
                created++;
                log.info("Category directory created: {} -> {}", category.getName(), epubStorage.categoryDir(category.getName()));
            }
        }
        return created;
    }

    /** Resolves (or creates) the category for a directory */
    @Transactional
    protected Category resolveCategory(String categoryName) {
        return categoryRepository.findByName(categoryName).orElseGet(() -> {
            Category created = categoryRepository.save(
                    new Category(categoryName, "Category created from the upload directory", 999));
            log.info("Category created for directory: {} (id={})", categoryName, created.getId());
            return created;
        });
    }
}
