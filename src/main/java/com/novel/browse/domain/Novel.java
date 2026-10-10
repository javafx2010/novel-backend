package com.novel.browse.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Novel master table.
 */
@Entity
@Table(name = "nb_novel", indexes = {
        @Index(name = "idx_novel_category", columnList = "category_id"),
        @Index(name = "idx_novel_views", columnList = "view_count")
})
public class Novel implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Serializing status */
    public static final String STATUS_ONGOING = "ONGOING";
    /** Completed status */
    public static final String STATUS_FINISHED = "FINISHED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(nullable = false, length = 64)
    private String author;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    /** Cover gradient color, avoids depending on external image resources */
    @Column(length = 128)
    private String coverColor;

    /** Character or symbol shown on the cover */
    @Column(length = 8)
    private String coverMark;

    @Column(name = "intro", length = 2000)
    private String description;

    @Column(length = 16, nullable = false)
    private String status = STATUS_ONGOING;

    /** Comma separated tags */
    @Column(length = 128)
    private String tags;

    @ColumnDefault("0")
    @Column(nullable = false)
    private Long viewCount = 0L;

    /** Rating from 0 to 10 with one decimal */
    @Column(nullable = false)
    private Double rating = 0.0;

    @Column(nullable = false)
    private Integer chapterCount = 0;

    @Column(nullable = false)
    private Long wordCount = 0L;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** Source EPUB file path relative to the upload directory; empty for manually created books */
    @Column(name = "source_file", length = 255)
    private String sourceFile;

    /** SHA-256 of the source EPUB content, used to deduplicate upload and startup scan */
    @Column(name = "source_hash", length = 64)
    private String sourceHash;

    @Transient
    public String getStatusText() {
        return STATUS_FINISHED.equals(status) ? "Finished" : "Ongoing";
    }

    @Transient
    public String[] getTagList() {
        if (tags == null || tags.isBlank()) {
            return new String[0];
        }
        return tags.split(",");
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public String getCoverColor() {
        return coverColor;
    }

    public void setCoverColor(String coverColor) {
        this.coverColor = coverColor;
    }

    public String getCoverMark() {
        return coverMark;
    }

    public void setCoverMark(String coverMark) {
        this.coverMark = coverMark;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public Long getViewCount() {
        return viewCount;
    }

    public void setViewCount(Long viewCount) {
        this.viewCount = viewCount;
    }

    public Double getRating() {
        return rating;
    }

    public void setRating(Double rating) {
        this.rating = rating;
    }

    public Integer getChapterCount() {
        return chapterCount;
    }

    public void setChapterCount(Integer chapterCount) {
        this.chapterCount = chapterCount;
    }

    public Long getWordCount() {
        return wordCount;
    }

    public void setWordCount(Long wordCount) {
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

    public String getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(String sourceFile) {
        this.sourceFile = sourceFile;
    }

    public String getSourceHash() {
        return sourceHash;
    }

    public void setSourceHash(String sourceHash) {
        this.sourceHash = sourceHash;
    }
}
