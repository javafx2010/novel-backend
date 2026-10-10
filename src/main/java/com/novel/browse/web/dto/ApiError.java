package com.novel.browse.web.dto;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * Unified error response body.
 */
public record ApiError(Instant timestamp, int status, String error, String message,
                       String path) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
