package com.novel.browse.web.dto;

/**
 * Google sign-in request: the ID Token issued by the Google Identity Services button
 */
public record GoogleLoginRequest(String credential) {
}
