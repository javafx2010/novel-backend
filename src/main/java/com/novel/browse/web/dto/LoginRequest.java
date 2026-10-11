package com.novel.browse.web.dto;

/**
 * Login request: the email (or username) plus the password
 */
public record LoginRequest(String email, String password) {
}
