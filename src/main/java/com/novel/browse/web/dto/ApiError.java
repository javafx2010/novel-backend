package com.novel.browse.web.dto;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * 统一错误响应体。
 */
public record ApiError(Instant timestamp, int status, String error, String message,
                       String path) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
