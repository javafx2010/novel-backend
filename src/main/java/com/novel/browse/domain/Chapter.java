package com.novel.browse.domain;

import jakarta.persistence.*;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Novel chapter.
 */
@Entity
@Table(name = "nb_chapter", uniqueConstraints = {
        @UniqueConstraint(name = "uk_chapter_novel_no", columnNames = {"novel_id", "chapter_no"})
})
public class Chapter implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "novel_id", nullable = false)
    private Novel novel;

    @Column(name = "chapter_no", nullable = false)
    private Integer chapterNo;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 200000)
    private String content;

    @Column(nullable = false)
    private Integer wordCount = 0;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Last time the chapter was written (set on every insert/update through the lifecycle callbacks).
     * Client apps compare it against {@code NovelSummary.updatedAt} to decide whether to re-fetch.
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Absolute URL the chapter text came from (crawler imports); null for EPUB uploads.
     * Used to skip already imported news items when the crawler runs again.
     */
    @Column(name = "source_url", length = 512)
    private String sourceUrl;

    @PrePersist
    @PreUpdate
    void stampUpdatedAt() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Novel getNovel() {
        return novel;
    }

    public void setNovel(Novel novel) {
        this.novel = novel;
    }

    public Integer getChapterNo() {
        return chapterNo;
    }

    public void setChapterNo(Integer chapterNo) {
        this.chapterNo = chapterNo;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getWordCount() {
        return wordCount;
    }

    public void setWordCount(Integer wordCount) {
        this.wordCount = wordCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }
}
