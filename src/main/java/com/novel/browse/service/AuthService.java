package com.novel.browse.service;

import com.novel.browse.domain.User;
import com.novel.browse.domain.UserSession;
import com.novel.browse.repository.UserRepository;
import com.novel.browse.repository.UserSessionRepository;
import com.novel.browse.web.UnauthorizedException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reader accounts: register, login, logout and the session cookie.
 * <p>
 * The cookie only carries a random token; {@link UserSession} keeps the owner and the expiry, so a session
 * can be revoked by logout and does not survive a server side cleanup.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final int PASSWORD_MIN = 8;
    private static final int PASSWORD_MAX = 64;
    private static final int USERNAME_MIN = 2;
    private static final int USERNAME_MAX = 32;

    /**
     * Letters, digits, underscore and CJK characters
     */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[\\w\\u4e00-\\u9fa5-]+$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final UserSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final int sessionDays;
    private final String cookieName;
    private final boolean cookieSecure;
    private final String cookieSameSite;

    public AuthService(UserRepository userRepository,
                       UserSessionRepository sessionRepository,
                       @Value("${app.auth.session-days:30}") int sessionDays,
                       @Value("${app.auth.cookie-name:nb_session}") String cookieName,
                       @Value("${app.auth.cookie-secure:false}") boolean cookieSecure,
                       @Value("${app.auth.cookie-same-site:Lax}") String cookieSameSite) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.sessionDays = Math.max(1, sessionDays);
        this.cookieName = cookieName;
        this.cookieSecure = cookieSecure;
        this.cookieSameSite = cookieSameSite;
    }

    /**
     * Registers a new account: username, email and password must all be unique or valid
     */
    @Transactional
    public User register(String username, String email, String password) {
        String name = validateUsername(username);
        String mail = validateEmail(email);
        validatePassword(password);

        if (userRepository.existsByUsernameIgnoreCase(name)) {
            throw new BadRequestException("error.auth.usernameTaken", name);
        }
        if (userRepository.existsByEmailIgnoreCase(mail)) {
            throw new BadRequestException("error.auth.emailRegistered", mail);
        }

        User user = new User();
        user.setUsername(name);
        user.setEmail(mail);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(user.getCreatedAt());
        User saved = userRepository.save(user);
        log.info("User registered: id={} username={} email={}", saved.getId(), saved.getUsername(), saved.getEmail());
        return saved;
    }

    /**
     * Logs in with the email (or username) plus password
     */
    @Transactional
    public User login(String identifier, String password) {
        String account = identifier == null ? "" : identifier.trim();
        if (account.isEmpty() || password == null || password.isEmpty()) {
            throw new BadRequestException("error.auth.credentialsRequired");
        }
        Optional<User> found = account.contains("@")
                ? userRepository.findByEmailIgnoreCase(account)
                : userRepository.findByUsernameIgnoreCase(account);
        User user = found.orElseThrow(() -> new UnauthorizedException("Email or password is incorrect"));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new UnauthorizedException("error.auth.badCredentials");
        }
        sessionRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        log.info("User logged in: id={} username={}", user.getId(), user.getUsername());
        return user;
    }

    /**
     * Creates a session row and returns its token
     */
    @Transactional
    public String issueSession(User user) {
        UserSession session = new UserSession();
        session.setToken(newToken());
        session.setUser(user);
        LocalDateTime now = LocalDateTime.now();
        session.setExpiresAt(now.plusDays(sessionDays));
        session.setCreatedAt(now);
        session.setLastAccessedAt(now);
        return sessionRepository.save(session).getToken();
    }

    /**
     * Drops the session behind a token; a blank token is ignored
     */
    @Transactional
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        sessionRepository.deleteByToken(token);
    }

    /**
     * Resolves the cookie token to its user; an expired or unknown token means "not logged in"
     */
    @Transactional(readOnly = true)
    public Optional<User> currentUser(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return sessionRepository.findByToken(token)
                .filter(session -> !session.isExpired(LocalDateTime.now()))
                .map(UserSession::getUser);
    }

    /**
     * Reads the session token from the request cookies
     */
    public String readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * Writes the session cookie, kept for {@code app.auth.session-days} days
     */
    public void writeSessionCookie(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(token).toString());
    }

    /**
     * Clears the session cookie on logout
     */
    public void clearSessionCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(cookieName, "")
                .maxAge(Duration.ZERO)
                .path("/")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .build().toString());
    }

    private ResponseCookie sessionCookie(String token) {
        return ResponseCookie.from(cookieName, token)
                .maxAge(Duration.ofDays(sessionDays))
                .path("/")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .build();
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private String validateUsername(String username) {
        String name = username == null ? "" : username.trim();
        if (name.length() < USERNAME_MIN || name.length() > USERNAME_MAX) {
            throw new BadRequestException("error.auth.usernameLength", USERNAME_MIN, USERNAME_MAX);
        }
        if (!USERNAME_PATTERN.matcher(name).matches()) {
            throw new BadRequestException("error.auth.usernamePattern");
        }
        return name;
    }

    private String validateEmail(String email) {
        String mail = email == null ? "" : email.trim().toLowerCase();
        if (mail.length() > 128 || !EMAIL_PATTERN.matcher(mail).matches()) {
            throw new BadRequestException("error.auth.emailInvalid", email);
        }
        return mail;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < PASSWORD_MIN || password.length() > PASSWORD_MAX) {
            throw new BadRequestException("error.auth.passwordLength", PASSWORD_MIN, PASSWORD_MAX);
        }
    }
}
