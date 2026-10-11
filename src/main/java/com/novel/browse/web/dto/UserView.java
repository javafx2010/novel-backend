package com.novel.browse.web.dto;

import com.novel.browse.domain.User;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Account information exposed to the client; the password hash is never included
 */
public record UserView(Long id, String username, String email, LocalDateTime createdAt) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static UserView of(User user) {
        return new UserView(user.getId(), user.getUsername(), user.getEmail(), user.getCreatedAt());
    }
}
