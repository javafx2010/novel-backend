package com.novel.browse.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;

import java.io.Serial;
import java.io.Serializable;

/**
 * Novel category.
 * <p>
 * A category belongs to one language: the same name in another language is another category
 * ({@code uk_category_name_language}), so the Chinese and the English library each have their own
 * "Fantasy" with their own description. A novel always points to the category of its own language.
 */
@Entity
@Table(name = "nb_category",
        uniqueConstraints = @UniqueConstraint(name = "uk_category_name_language",
                columnNames = {"name", "language"}),
        indexes = @Index(name = "idx_category_language", columnList = "language"))
public class Category implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String name;

    @Column(length = 128)
    private String description;

    @Column(nullable = false)
    private Integer sortNo = 0;

    /**
     * Language of this category ({@code en} / {@code zh_CN}), same tag as {@code nb_novel.language}.
     * <p>
     * The column default matters for upgrades: {@code ddl-auto=update} issues
     * {@code alter table nb_category add column language varchar(16) not null}, which fails on H2 as soon as
     * the table already holds rows.
     * </p>
     */
    @ColumnDefault("'en'")
    @Column(name = "language", length = 16, nullable = false)
    private String language = Novel.DEFAULT_LANGUAGE;

    public Category() {
    }

    public Category(String name, String description, Integer sortNo, String language) {
        this.name = name;
        this.description = description;
        this.sortNo = sortNo;
        this.language = language == null || language.isBlank() ? Novel.DEFAULT_LANGUAGE : language;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getSortNo() {
        return sortNo;
    }

    public void setSortNo(Integer sortNo) {
        this.sortNo = sortNo;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }
}
