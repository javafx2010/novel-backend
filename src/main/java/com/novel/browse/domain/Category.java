package com.novel.browse.domain;

import jakarta.persistence.*;

import java.io.Serial;
import java.io.Serializable;

/**
 * 小说分类，如玄幻、都市、悬疑等。
 */
@Entity
@Table(name = "nb_category")
public class Category implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String name;

    @Column(length = 128)
    private String description;

    @Column(nullable = false)
    private Integer sortNo = 0;

    public Category() {
    }

    public Category(String name, String description, Integer sortNo) {
        this.name = name;
        this.description = description;
        this.sortNo = sortNo;
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
}
