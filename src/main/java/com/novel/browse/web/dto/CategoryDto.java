package com.novel.browse.web.dto;

import com.novel.browse.domain.Category;

import java.io.Serial;
import java.io.Serializable;

/**
 * Category information: a category belongs to one language, the same name in another language is another
 * category with its own description.
 */
public record CategoryDto(Long id, String name, String description, Integer sortNo, String language)
        implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static CategoryDto of(Category category) {
        return new CategoryDto(category.getId(), category.getName(), category.getDescription(), category.getSortNo(),
                category.getLanguage());
    }
}
