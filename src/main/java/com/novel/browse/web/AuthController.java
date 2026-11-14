package com.novel.browse.web;

import com.novel.browse.domain.User;
import com.novel.browse.service.AuthService;
import com.novel.browse.web.dto.LoginRequest;
import com.novel.browse.web.dto.RegisterRequest;
import com.novel.browse.web.dto.UserView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reader accounts: register and login answer with the account and set the session cookie
 * (kept for {@code app.auth.session-days} days, 30 by default).
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Registers with username, email and password; logs the new account in right away
     */
    @PostMapping("/register")
    public UserView register(@RequestBody RegisterRequest body, HttpServletResponse response) {
        User user = authService.register(body.username(), body.email(), body.password());
        authService.writeSessionCookie(response, authService.issueSession(user));
        return UserView.of(user);
    }

    /**
     * Logs in with the email (or username) and password
     */
    @PostMapping("/login")
    public UserView login(@RequestBody LoginRequest body, HttpServletResponse response) {
        User user = authService.login(body.email(), body.password());
        authService.writeSessionCookie(response, authService.issueSession(user));
        return UserView.of(user);
    }

    /**
     * Drops the session and clears the cookie
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(authService.readToken(request));
        authService.clearSessionCookie(response);
    }

    /**
     * The logged in account, 401 when the cookie is missing or expired
     */
    @GetMapping("/me")
    public UserView me(@CurrentUser User user) {
        return UserView.of(user);
    }
}
