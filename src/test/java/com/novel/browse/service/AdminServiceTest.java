package com.novel.browse.service;

import com.novel.browse.domain.Category;
import com.novel.browse.domain.Novel;
import com.novel.browse.repository.CategoryRepository;
import com.novel.browse.repository.ChapterRepository;
import com.novel.browse.repository.NovelRepository;
import com.novel.browse.service.epub.EpubBook;
import com.novel.browse.service.epub.EpubChapter;
import com.novel.browse.service.epub.EpubParser;
import com.novel.browse.service.epub.EpubStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminServiceTest {

    private EpubParser epubParser;
    private EpubStorage epubStorage;
    private CoverStorage coverStorage;
    private NovelRepository novelRepository;
    private ChapterRepository chapterRepository;
    private CategoryRepository categoryRepository;
    private AdminService adminService;

    @BeforeEach
    void setUp() {
        epubParser = mock(EpubParser.class);
        epubStorage = mock(EpubStorage.class);
        coverStorage = mock(CoverStorage.class);
        novelRepository = mock(NovelRepository.class);
        chapterRepository = mock(ChapterRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        adminService = new AdminService(epubParser, epubStorage, coverStorage, mock(NovelService.class),
                novelRepository, chapterRepository, categoryRepository, "Imported");

        when(epubStorage.sha256(any(byte[].class))).thenReturn("hash-2");
        when(novelRepository.existsBySourceHash(anyString())).thenReturn(false);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(new Category("Fantasy", "", 1)));
        when(epubStorage.save(any(byte[].class), anyString(), anyString())).thenReturn("Fantasy/hash-2-book.epub");
        when(novelRepository.save(any(Novel.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void uploadOverwritesTheBookWithTheSameTitleAndAuthor() {
        Novel existing = new Novel();
        existing.setId(42L);
        existing.setTitle("The Nine Rungs of Frost");
        existing.setAuthor("Yulan Vey");
        existing.setViewCount(17L);
        existing.setRating(4.5);
        existing.setSourceFile("Fantasy/hash-1-old.epub");
        existing.setCoverImage("old-cover.jpg");

        when(epubParser.parse(any(byte[].class), anyString()))
                .thenReturn(book("The Nine Rungs of Frost", "Yulan Vey", "New edition", "Chapter One",
                        new byte[]{9, 9, 9}));
        when(novelRepository.findFirstByTitleIgnoreCaseAndAuthorIgnoreCase("The Nine Rungs of Frost", "Yulan Vey"))
                .thenReturn(Optional.of(existing));
        when(coverStorage.save(any(byte[].class), anyString(), anyString())).thenReturn("new-cover.jpg");

        Novel saved = adminService.upload(new byte[]{1, 2, 3}, "book.epub", 1L, null, null, null);

        // same row, no copy
        assertSame(existing, saved);
        assertEquals(42L, saved.getId());
        assertEquals("New edition", saved.getDescription());
        // chapters are replaced, not appended
        verify(chapterRepository).deleteByNovelId(42L);
        verify(chapterRepository).saveAll(any(List.class));
        assertEquals(1, saved.getChapterCount());
        // runtime counters of the existing book survive an overwrite
        assertEquals(17L, saved.getViewCount());
        assertEquals(4.5, saved.getRating(), 0.001);
        // previous source file and cover of the replaced edition are removed
        verify(epubStorage).delete("Fantasy/hash-1-old.epub");
        verify(coverStorage).delete("old-cover.jpg");
        assertEquals("Fantasy/hash-2-book.epub", saved.getSourceFile());
        assertEquals("hash-2", saved.getSourceHash());
    }

    @Test
    void uploadKeepsTheCoverWhenTheNewEpubHasNone() {
        Novel existing = new Novel();
        existing.setId(7L);
        existing.setTitle("Coverless Re-upload");
        existing.setAuthor("Someone");
        existing.setCoverImage("kept-cover.jpg");

        when(epubParser.parse(any(byte[].class), anyString()))
                .thenReturn(book("Coverless Re-upload", "Someone", "desc", "Chapter One"));
        when(novelRepository.findFirstByTitleIgnoreCaseAndAuthorIgnoreCase("Coverless Re-upload", "Someone"))
                .thenReturn(Optional.of(existing));

        Novel saved = adminService.upload(new byte[]{1, 2, 3}, "book.epub", 1L, null, null, null);

        assertEquals("kept-cover.jpg", saved.getCoverImage());
        verify(coverStorage, never()).delete(anyString());
    }

    @Test
    void uploadCreatesANewBookWhenTheTitleIsUnknown() {
        when(epubParser.parse(any(byte[].class), anyString())).thenReturn(book("A Brand New Book", "Someone", "d", "c"));
        when(novelRepository.findFirstByTitleIgnoreCaseAndAuthorIgnoreCase(anyString(), anyString()))
                .thenReturn(Optional.empty());

        Novel saved = adminService.upload(new byte[]{1, 2, 3}, "book.epub", 1L, null, null, null);

        assertEquals("A Brand New Book", saved.getTitle());
        assertEquals(0L, saved.getViewCount());
        assertEquals(1, saved.getChapterCount());
        assertEquals("Fantasy/hash-2-book.epub", saved.getSourceFile());
        verify(chapterRepository, never()).deleteByNovelId(anyLong());
        verify(chapterRepository).saveAll(any(List.class));
    }

    private static EpubBook book(String title, String author, String description, String chapterTitle) {
        return book(title, author, description, chapterTitle, null);
    }

    private static EpubBook book(String title, String author, String description, String chapterTitle,
                                 byte[] coverImage) {
        String mediaType = coverImage == null ? null : "image/jpeg";
        return new EpubBook(title, author, description, "zh-CN", List.of(),
                List.of(new EpubChapter(chapterTitle, "Some chapter text.")), coverImage, mediaType);
    }
}
