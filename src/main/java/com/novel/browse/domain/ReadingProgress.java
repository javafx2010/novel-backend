package com.novel.browse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.ColumnDefault;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * How far a reader got in a book: the last chapter read and how much of it; the pair (user, novel) is unique
 */
@Entity
@Table(name = "nb_read_progress",
        uniqueConstraints = @UniqueConstraint(name = "uk_progress_user_novel", columnNames = {"user_id", "novel_id"}),
        indexes = @Index(name = "idx_progress_user", columnList = "user_id"))
public class ReadingProgress implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "novel_id", nullable = false)
    private Novel novel;

    /**
     * Chapter number the reader stopped at, 1 based
     */
    @Column(name = "chapter_no", nullable = false)
    private Integer chapterNo = 1;

    /**
     * How much of that chapter the reader went through, 0-100; the whole book percentage is derived from the
     * two (see {@code ProgressVo}), so a reader can resume in the middle of a long chapter.
     * <p>
     * The column default matters for upgrades: {@code ddl-auto=update} issues
     * {@code alter table nb_read_progress add column chapter_percent int not null}, which fails on H2 as soon
     * as the table already holds rows.
     * </p>
     */
    @ColumnDefault("0")
    @Column(name = "chapter_percent", nullable = false)
    private Integer chapterPercent = 0;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
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

    public Integer getChapterPercent() {
        return chapterPercent;
    }

    public void setChapterPercent(Integer chapterPercent) {
        this.chapterPercent = chapterPercent;
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
}
