package com.novel.browse.service.epub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpubStorageTest {

    @TempDir
    Path tempDir;

    private EpubStorage storage() {
        return new EpubStorage(tempDir.toString());
    }

    @Test
    void fileNameIsStableAndSafe() {
        EpubStorage storage = storage();
        byte[] data = "hello epub".getBytes(StandardCharsets.UTF_8);

        String name1 = storage.fileName(data, "/tmp/我的 书@2.epub");
        String name2 = storage.fileName(data, "我的 书@2.epub");

        assertEquals(name1, name2, "Identical content must produce the same file name (idempotent)");
        assertFalse(name1.contains("/"), "File name must not contain a path separator: " + name1);
        assertFalse(name1.contains(".."), "File name must not contain .. : " + name1);
        assertTrue(name1.endsWith(".epub"));
        assertTrue(name1.startsWith(storage.sha256(data).substring(0, 8)), name1);
    }

    @Test
    void saveIntoCategoryDir() throws Exception {
        EpubStorage storage = storage();
        byte[] data = "epub content".getBytes(StandardCharsets.UTF_8);

        String relative = storage.save(data, "book.epub", "玄幻奇幻");

        assertEquals("玄幻奇幻/" + storage.fileName(data, "book.epub"), relative, "相对路径应为 分类目录/文件名");
        Path file = tempDir.resolve(relative);
        assertTrue(Files.isDirectory(tempDir.resolve("玄幻奇幻")), "分类子目录应自动创建");
        assertTrue(Files.exists(file), "The file must be stored inside the category subdirectory");

        // Saving identical content again: still a single file
        assertEquals(relative, storage.save(data, "book.epub", "玄幻奇幻"));

        storage.delete(relative);
        assertFalse(Files.exists(file));
    }

    @Test
    void scanMapsSubDirToCategory() throws Exception {
        EpubStorage storage = storage();
        storage.save("a".getBytes(StandardCharsets.UTF_8), "a.epub", "都市言情");
        storage.save("b".getBytes(StandardCharsets.UTF_8), "b.epub", "都市言情");
        storage.save("c".getBytes(StandardCharsets.UTF_8), "c.epub", "悬疑推理");
        Files.write(tempDir.resolve("root.epub"), "root".getBytes(StandardCharsets.UTF_8));

        List<EpubStorage.ScannedFile> scanned = storage.scan();

        assertEquals(4, scanned.size());
        assertEquals(2, scanned.stream().filter(f -> "都市言情".equals(f.categoryName())).count());
        assertEquals(1, scanned.stream().filter(f -> "悬疑推理".equals(f.categoryName())).count());
        // Files in the root have no category directory, the caller assigns the default category
        assertEquals(1, scanned.stream().filter(f -> f.categoryName() == null).count());
        assertTrue(scanned.stream().anyMatch(f -> "root.epub".equals(f.path().getFileName().toString())));
        assertNull(scanned.stream()
                .filter(f -> "root.epub".equals(f.path().getFileName().toString()))
                .findFirst().orElseThrow().categoryName());
    }

    @Test
    void safeDirNameStripsIllegalChars() {
        EpubStorage storage = storage();
        assertEquals("玄幻_奇幻", storage.safeDirName("玄幻/奇幻"));
        assertEquals("Uncategorized", storage.safeDirName("  "));
        assertEquals("科幻_A_B", storage.safeDirName("科幻:A?B"));
        assertEquals("A_B_C", storage.safeDirName("A B C"));
    }

    @Test
    void ensureCategoryDirIsIdempotent() {
        EpubStorage storage = storage();
        assertTrue(storage.ensureCategoryDir("武侠修真"));
        assertFalse(storage.ensureCategoryDir("武侠修真"), "An existing directory must not be created again");
        assertTrue(Files.isDirectory(tempDir.resolve("武侠修真")));
    }

    @Test
    void deleteRejectsPathTraversal() throws Exception {
        EpubStorage storage = storage();
        String relative = storage.save("x".getBytes(StandardCharsets.UTF_8), "x.epub", "测试");

        storage.delete("../../etc/passwd.epub");
        storage.delete("../../../tmp/evil.epub");

        assertTrue(Files.exists(tempDir.resolve(relative)), "A traversal delete must not touch files inside the directory");
    }

    @Test
    void sha256OfKnownInput() {
        EpubStorage storage = storage();
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                storage.sha256(data));
    }
}
