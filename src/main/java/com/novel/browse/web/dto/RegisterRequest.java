package com.novel.browse.web.dto;

/**
 * Register request: username, email and password are all required
 */
public record RegisterRequest(String username, String email, String password) {
}
