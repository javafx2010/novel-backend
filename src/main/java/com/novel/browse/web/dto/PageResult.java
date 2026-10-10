package com.novel.browse.web.dto;

import org.springframework.data.domain.Page;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.function.Function;

/**
 * Unified paginated response.
 */
public record PageResult<T>(List<T> content, int page, int size, long totalElements,
                            int totalPages, boolean first, boolean last) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static <T> PageResult<T> of(List<T> content, Page<?> page) {
        return new PageResult<>(content, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.isFirst(), page.isLast());
    }

    public static <E, T> PageResult<T> of(Page<E> page, Function<E, T> mapper) {
        return of(page.getContent().stream().map(mapper).toList(), page);
    }

    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(List.of(), page, size, 0L, 0, true, true);
    }
}
