package com.novel.browse.service;

import com.novel.browse.domain.Category;
import com.novel.browse.domain.Novel;
import com.novel.browse.i18n.I18nSettings;
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
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private NovelService novelService;
    private AdminService adminService;

    @BeforeEach
    void setUp() {
        epubParser = mock(EpubParser.class);
        epubStorage = mock(EpubStorage.class);
        coverStorage = mock(CoverStorage.class);
        novelRepository = mock(NovelRepository.class);
        chapterRepository = mock(ChapterRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        novelService = mock(NovelService.class);
        adminService = new AdminService(epubParser, epubStorage, coverStorage, novelService,
                novelRepository, chapterRepository, categoryRepository,
                new I18nSettings(Locale.ENGLISH, List.of(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE)), "Imported");

        when(epubStorage.sha256(any(byte[].class))).thenReturn("hash-2");
        when(novelRepository.existsBySourceHash(anyString())).thenReturn(false);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(new Category("Fantasy", "", 1, "en")));
        // a category is per language: the same name in another language is another row, with its own description
        when(novelService.findOrCreateCategory(anyString(), anyString(), any()))
                .thenAnswer(invocation -> new Category(invocation.getArgument(0),
                        invocation.getArgument(2) == null ? "" : invocation.getArgument(2),
                        9, invocation.getArgument(1)));
        when(epubStorage.save(any(byte[].class), anyString(), anyString())).thenReturn("Fantasy/hash-2-book.epub");
        when(novelRepository.save(any(Novel.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void uploadUsesTheCategoryOfTheLanguageOfTheBook() {
        when(epubParser.parse(any(byte[].class), anyString()))
                .thenReturn(book("剑痕录", "佚名", "d", "zh-CN", "第一章"));
        when(novelRepository.findFirstByTitleIgnoreCaseAndAuthorIgnoreCaseAndLanguageIgnoreCase(
                anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        Novel saved = adminService.upload(new byte[]{1, 2, 3}, "book.epub", null, "玄幻奇幻",
                "御剑乘风，问长生何处", null, null, null, null);

        // the category of the Chinese library carries the Chinese description
        Category category = saved.getCategory();
        assertEquals("玄幻奇幻", category.getName());
        assertEquals("zh_CN", category.getLanguage());
        assertEquals("御剑乘风，问长生何处", category.getDescription());
        verify(novelService).findOrCreateCategory("玄幻奇幻", "zh_CN", "御剑乘风，问长生何处");
    }

    @Test
    void uploadRejectsACategoryOfAnotherLanguage() {
        when(epubParser.parse(any(byte[].class), anyString()))
                .thenReturn(book("剑痕录", "佚名", "d", "zh-CN", "第一章"));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> adminService.upload(new byte[]{1, 2, 3}, "book.epub", 1L, null, null, null, null, null, null));
        assertEquals("error.category.languageMismatch", error.code());
        verify(novelRepository, never()).save(any(Novel.class));
    }

    @Test
    void uploadCreatesASecondEditionInTheCategoryOfItsOwnLanguage() {
        when(epubParser.parse(any(byte[].class), anyString()))
                .thenReturn(book("剑痕录", "佚名", "d", "en", "Chapter One"));
        when(novelRepository.findFirstByTitleIgnoreCaseAndAuthorIgnoreCaseAndLanguageIgnoreCase(
                anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        Novel saved = adminService.upload(new byte[]{1, 2, 3}, "book.epub", null, "玄幻奇幻",
                "Swords and immortals", null, null, "en", null);

        assertEquals("en", saved.getCategory().getLanguage());
        assertEquals("Swords and immortals", saved.getCategory().getDescription());
        verify(novelService).findOrCreateCategory("玄幻奇幻", "en", "Swords and immortals");
    }

    @Test
    void uploadOverwritesTheBookWithTheSameTitleAndAuthor() {
        Novel existing = new Novel();
        existing.setId(42L);
        existing.setTitle("The Nine Rungs of Frost");
        existing.setAuthor("Yulan Vey");
        existing.setLanguage("zh_CN");
        existing.setVersionGroup("group-1");
        existing.setViewCount(17L);
        existing.setRating(4.5);
        existing.setSourceFile("Fantasy/hash-1-old.epub");
        existing.setCoverImage("old-cover.jpg");

        when(epubParser.parse(any(byte[].class), anyString()))
                .thenReturn(book("The Nine Rungs of Frost", "Yulan Vey", "New edition", "zh-CN", "Chapter One",
                        new byte[]{9, 9, 9}));
        when(novelRepository.findFirstByTitleIgnoreCaseAndAuthorIgnoreCaseAndLanguageIgnoreCase(
                "The Nine Rungs of Frost", "Yulan Vey", "zh_CN")).thenReturn(Optional.of(existing));
        when(coverStorage.save(any(byte[].class), anyString(), anyString())).thenReturn("new-cover.jpg");

        Novel saved = adminService.upload(new byte[]{1, 2, 3}, "book.epub", null, "Fantasy", null, null, null,
                null, null);

        // same row, no copy
        assertSame(existing, saved);
        assertEquals(42L, saved.getId());
        assertEquals("New edition", saved.getDescription());
        assertEquals("zh_CN", saved.getLanguage());
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
        existing.setLanguage("en");
        existing.setCoverImage("kept-cover.jpg");

        when(epubParser.parse(any(byte[].class), anyString()))
                .thenReturn(book("Coverless Re-upload", "Someone", "desc", "en", "Chapter One"));
        when(novelRepository.findFirstByTitleIgnoreCaseAndAuthorIgnoreCaseAndLanguageIgnoreCase(
                "Coverless Re-upload", "Someone", "en")).thenReturn(Optional.of(existing));

        Novel saved = adminService.upload(new byte[]{1, 2, 3}, "book.epub", 1L, null, null, null, null, null, null);

        assertEquals("kept-cover.jpg", saved.getCoverImage());
        verify(coverStorage, never()).delete(anyString());
    }

    @Test
    void uploadCreatesANewBookWhenTheTitleIsUnknown() {
        when(epubParser.parse(any(byte[].class), anyString()))
                .thenReturn(book("A Brand New Book", "Someone", "d", "en", "c"));

        Novel saved = adminService.upload(new byte[]{1, 2, 3}, "book.epub", 1L, null, null, null, null, null, null);

        assertEquals("A Brand New Book", saved.getTitle());
        assertEquals(0L, saved.getViewCount());
        assertEquals(1, saved.getChapterCount());
        assertEquals("en", saved.getLanguage());
        assertNotNull(saved.getVersionGroup());
        assertEquals("Fantasy/hash-2-book.epub", saved.getSourceFile());
        verify(chapterRepository, never()).deleteByNovelId(anyLong());
        verify(chapterRepository).saveAll(any(List.class));
    }

    @Test
    void uploadTakesTheLanguageFromTheEpubWhenNoneIsRequested() {
        when(epubParser.parse(any(byte[].class), anyString()))
                .thenReturn(book("剑痕录", "佚名", "d", "zh-CN", "第一章"));

        Novel saved = adminService.upload(new byte[]{1, 2, 3}, "book.epub", null, "Fantasy", null, null, null,
                null, null);

        assertEquals("zh_CN", saved.getLanguage());
        verify(novelRepository)
                .findFirstByTitleIgnoreCaseAndAuthorIgnoreCaseAndLanguageIgnoreCase("剑痕录", "佚名", "zh_CN");
    }

    @Test
    void uploadCreatesASeparateEditionForAnotherLanguageOfTheSameWork() {
        Novel chinese = new Novel();
        chinese.setId(11L);
        chinese.setTitle("剑痕录");
        chinese.setAuthor("佚名");
        chinese.setLanguage("zh_CN");
        chinese.setVersionGroup("group-1");

        when(epubParser.parse(any(byte[].class), anyString()))
                .thenReturn(book("剑痕录", "佚名", "d", "en", "Chapter One"));
        when(novelRepository.findFirstByTitleIgnoreCaseAndAuthorIgnoreCaseAndLanguageIgnoreCase("剑痕录", "佚名", "en"))
                .thenReturn(Optional.empty());
        when(novelRepository.findFirstByTitleIgnoreCaseAndAuthorIgnoreCase("剑痕录", "佚名"))
                .thenReturn(Optional.of(chinese));

        Novel english = adminService.upload(new byte[]{1, 2, 3}, "book.epub", 1L, null, null, null, null, "en", null);

        // a second edition, not an overwrite of the Chinese one
        assertNotSame(chinese, english);
        assertEquals("en", english.getLanguage());
        // both editions belong to the same work
        assertEquals("group-1", english.getVersionGroup());
        verify(chapterRepository, never()).deleteByNovelId(anyLong());
    }

    @Test
    void uploadStartsAVersionGroupForTheFirstEditionOfAWork() {
        when(epubParser.parse(any(byte[].class), anyString()))
                .thenReturn(book("A Brand New Book", "Someone", "d", "zh", "第一章"));

        Novel saved = adminService.upload(new byte[]{1, 2, 3}, "book.epub", null, "Fantasy", null, null, null,
                null, null);

        assertEquals("zh_CN", saved.getLanguage());
        assertNotNull(saved.getVersionGroup());
    }

    private static EpubBook book(String title, String author, String description, String language,
                                 String chapterTitle) {
        return book(title, author, description, language, chapterTitle, null);
    }

    private static EpubBook book(String title, String author, String description, String language,
                                 String chapterTitle, byte[] coverImage) {
        String mediaType = coverImage == null ? null : "image/jpeg";
        return new EpubBook(title, author, description, language, List.of(),
                List.of(new EpubChapter(chapterTitle, "Some chapter text.")), coverImage, mediaType);
    }
}
