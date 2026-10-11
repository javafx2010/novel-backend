package com.novel.browse.web;

import com.novel.browse.domain.Category;
import com.novel.browse.domain.Novel;
import com.novel.browse.domain.UserSession;
import com.novel.browse.repository.CategoryRepository;
import com.novel.browse.repository.NovelRepository;
import com.novel.browse.repository.UserSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Accounts, bookshelf, reading progress and reviews over HTTP, including the session cookie
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountApiTest {

    private static final Pattern TOKEN = Pattern.compile("nb_session=([^;]+)");
    private static final long THIRTY_DAYS = 30L * 24 * 60 * 60;
    private static final String PASSWORD = "secret123";

    @TempDir
    static Path dir;
    /**
     * Static counter: JUnit builds a new instance per test method but the database stays shared
     */
    private static int seq;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    @Value("${local.server.port}")
    private int port;
    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private NovelRepository novelRepository;

    @Autowired
    private UserSessionRepository sessionRepository;
    private String lastTitle;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.db.snapshot.file", () -> dir.resolve("db.sql").toString());
        registry.add("app.upload.dir", () -> dir.resolve("epub").toString());
        registry.add("app.cover.dir", () -> dir.resolve("covers").toString());
        registry.add("rocksdb.cache.path", () -> dir.resolve("cache").toString());
        registry.add("app.upload.scan-on-start", () -> "false");
    }

    private static String token(String setCookieHeader) {
        Matcher matcher = TOKEN.matcher(setCookieHeader == null ? "" : setCookieHeader);
        assertTrue(matcher.find(), "no nb_session cookie in: " + setCookieHeader);
        return matcher.group(1);
    }

    @Test
    void registerSetsASessionCookieValidForThirtyDays() {
        Response response = register("reader_one", "one@example.com");

        assertEquals(200, response.status());
        assertEquals("reader_one", response.body().path("username").asString());
        assertEquals("one@example.com", response.body().path("email").asString());
        assertTrue(response.body().path("password").isMissingNode(), "the password hash must never be returned");

        String cookie = response.setCookie();
        assertNotNull(cookie, "session cookie should be set");
        assertTrue(cookie.contains("Max-Age=" + THIRTY_DAYS), "cookie should live 30 days: " + cookie);
        assertTrue(cookie.contains("HttpOnly"), "cookie should be HttpOnly: " + cookie);
        assertTrue(cookie.contains("SameSite=Lax"), "cookie should be SameSite=Lax: " + cookie);
        assertTrue(cookie.contains("Path=/"), "cookie should apply to the whole site: " + cookie);

        String token = token(cookie);
        Response me = call("GET", "/auth/me", null, token);
        assertEquals(200, me.status());
        assertEquals("one@example.com", me.body().path("email").asString());
    }

    @Test
    void registerRejectsDuplicatesAndWeakInput() {
        register("dup_user", "dup@example.com");

        Response sameEmail = register("other_user", "DUP@example.com");
        assertEquals(400, sameEmail.status());
        assertEquals("Email is already registered: dup@example.com", sameEmail.body().path("message").asString());

        Response sameName = register("DUP_USER", "another@example.com");
        assertEquals(400, sameName.status());
        assertEquals("Username is already taken: DUP_USER", sameName.body().path("message").asString());

        Response badEmail = register("bad_mail", "not-an-email");
        assertEquals(400, badEmail.status());
        assertTrue(badEmail.body().path("message").asString().contains("Email is not valid"),
                badEmail.body().toString());

        Response shortPassword = call("POST", "/auth/register",
                "{\"username\":\"short_pw\",\"email\":\"short@example.com\",\"password\":\"123\"}", null);
        assertEquals(400, shortPassword.status());
        assertEquals("Password must be 8-64 characters", shortPassword.body().path("message").asString());
    }

    @Test
    void loginChecksThePasswordAndLogoutEndsTheSession() {
        register("login_user", "login@example.com");

        Response wrong = call("POST", "/auth/login",
                json(new LoginRequest("login@example.com", "wrong-password")), null);
        assertEquals(401, wrong.status());
        assertEquals("Email or password is incorrect", wrong.body().path("message").asString());

        Response loggedIn = call("POST", "/auth/login",
                json(new LoginRequest("login@example.com", PASSWORD)), null);
        assertEquals(200, loggedIn.status());
        assertEquals("login_user", loggedIn.body().path("username").asString());
        String cookie = token(loggedIn.setCookie());

        // the username works as well
        Response byName = call("POST", "/auth/login", json(new LoginRequest("login_user", PASSWORD)), null);
        assertEquals(200, byName.status());

        Response logout = call("POST", "/auth/logout", null, cookie);
        assertEquals(204, logout.status());
        assertTrue(logout.setCookie().contains("Max-Age=0"), "logout should clear the cookie: " + logout.setCookie());

        assertEquals(401, call("GET", "/auth/me", null, cookie).status());
    }

    @Test
    void protectedEndpointsRequireALogin() {
        Long novelId = newNovel("未登录的书");

        assertEquals(401, call("GET", "/me/favorites", null, null).status());
        assertEquals(401, call("POST", "/me/favorites/" + novelId, null, null).status());
        assertEquals(401, call("GET", "/me/progress", null, null).status());
        assertEquals(401, call("PUT", "/me/reviews/" + novelId, "{\"rating\":5}", null).status());
        assertEquals(401, call("GET", "/me/likes", null, null).status());
        assertEquals(401, call("POST", "/me/likes/" + novelId, null, null).status());
        assertEquals(401, call("DELETE", "/me/likes/" + novelId, null, null).status());
        assertEquals(401, call("GET", "/auth/me", null, "not-a-real-token").status());
    }

    @Test
    void browsingTheLibraryNeedsNoLogin() throws Exception {
        Long novelId = newNovel("随便翻翻的书");
        List<String> publicPaths = List.of(
                "/home",
                "/categories",
                "/novels?page=0&size=10",
                "/rank",
                "/search?keyword=" + URLEncoder.encode(lastTitle, StandardCharsets.UTF_8),
                "/novels/" + novelId,
                "/novels/" + novelId + "/chapters");
        for (String path : publicPaths) {
            assertEquals(200, call("GET", path, null, null).status(),
                    path + " should be readable without a login");
        }
    }

    @Test
    void likesAreCountedOncePerReaderAndCanBeTakenBack() {
        String first = login("liker_a", "liker_a@example.com");
        String second = login("liker_b", "liker_b@example.com");
        Long novelId = newNovel("被点赞的书");

        Response liked = call("POST", "/me/likes/" + novelId, null, first);
        assertEquals(200, liked.status());
        assertTrue(liked.body().path("liked").asBoolean());
        assertEquals(1, liked.body().path("likeCount").asInt());

        // liking twice must not count twice for the same reader
        assertEquals(1, call("POST", "/me/likes/" + novelId, null, first).body().path("likeCount").asInt());

        assertEquals(2, call("POST", "/me/likes/" + novelId, null, second).body().path("likeCount").asInt());
        assertEquals(2, novelRepository.findById(novelId).orElseThrow().getLikeCount());

        assertEquals(1, call("DELETE", "/me/likes/" + novelId, null, second).body().path("likeCount").asInt());
        assertFalse(call("GET", "/me/likes/" + novelId, null, second).body().path("liked").asBoolean());
        assertTrue(call("GET", "/me/likes/" + novelId, null, first).body().path("liked").asBoolean());
        assertEquals(1, call("GET", "/me/likes", null, first).body().path("totalElements").asInt());
    }

    @Test
    void anExpiredSessionIsNoLongerAccepted() {
        register("expire_user", "expire@example.com");
        String cookie = token(call("POST", "/auth/login",
                json(new LoginRequest("expire@example.com", PASSWORD)), null).setCookie());

        UserSession session = sessionRepository.findAll().stream()
                .filter(s -> cookie.equals(s.getToken()))
                .findFirst().orElseThrow();
        session.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        sessionRepository.save(session);

        assertEquals(401, call("GET", "/auth/me", null, cookie).status());
    }

    @Test
    void bookshelfAddsListsAndRemoves() {
        String cookie = login("shelf_user", "shelf@example.com");
        Long novelId = newNovel("书架里的书");

        assertFalse(call("GET", "/me/favorites/" + novelId, null, cookie)
                .body().path("favorited").asBoolean());

        assertTrue(call("POST", "/me/favorites/" + novelId, null, cookie)
                .body().path("favorited").asBoolean());
        // adding twice must not create a second entry
        call("POST", "/me/favorites/" + novelId, null, cookie);

        Response list = call("GET", "/me/favorites", null, cookie);
        assertEquals(200, list.status());
        assertEquals(1, list.body().path("totalElements").asInt());
        assertEquals(lastTitle, list.body().path("content").get(0).path("title").asString());

        assertEquals(204, call("DELETE", "/me/favorites/" + novelId, null, cookie).status());
        assertFalse(call("GET", "/me/favorites/" + novelId, null, cookie)
                .body().path("favorited").asBoolean());
    }

    @Test
    void readingProgressIsRememberedPerBook() {
        String cookie = login("progress_user", "progress@example.com");
        Long novelId = newNovel("在读的书");

        Response saved = call("PUT", "/me/progress/" + novelId, "{\"chapterNo\":2}", cookie);
        assertEquals(200, saved.status());
        assertEquals(2, saved.body().path("chapterNo").asInt());

        assertEquals(2, call("GET", "/me/progress/" + novelId, null, cookie)
                .body().path("chapterNo").asInt());

        Response tooFar = call("PUT", "/me/progress/" + novelId, "{\"chapterNo\":99}", cookie);
        assertEquals(400, tooFar.status());

        assertEquals(1, call("GET", "/me/progress", null, cookie).body().path("totalElements").asInt());

        assertEquals(204, call("DELETE", "/me/progress/" + novelId, null, cookie).status());
        assertEquals(404, call("GET", "/me/progress/" + novelId, null, cookie).status());
    }

    @Test
    void reviewsAreOnePerReaderAndRefreshTheNovelRating() {
        String first = login("reviewer_a", "reviewer_a@example.com");
        String second = login("reviewer_b", "reviewer_b@example.com");
        Long novelId = newNovel("被评分的书");

        Response review = call("PUT", "/me/reviews/" + novelId,
                "{\"rating\":5,\"content\":\"很好看\"}", first);
        assertEquals(200, review.status());
        assertEquals(5, review.body().path("rating").asInt());
        assertEquals("reviewer_a", review.body().path("username").asString());
        assertEquals(10.0, novelRepository.findById(novelId).orElseThrow().getRating(), 0.001);
        assertEquals(1, novelRepository.findById(novelId).orElseThrow().getRatingCount());

        // the same reader updates the review instead of adding a second one
        assertEquals(3, call("PUT", "/me/reviews/" + novelId, "{\"rating\":3}", first)
                .body().path("rating").asInt());
        assertEquals(6.0, novelRepository.findById(novelId).orElseThrow().getRating(), 0.001);

        call("PUT", "/me/reviews/" + novelId, "{\"rating\":4}", second);
        assertEquals(7.0, novelRepository.findById(novelId).orElseThrow().getRating(), 0.001);
        assertEquals(2, novelRepository.findById(novelId).orElseThrow().getRatingCount());

        Response publicList = call("GET", "/novels/" + novelId + "/reviews", null, null);
        assertEquals(200, publicList.status());
        assertEquals(2, publicList.body().path("totalElements").asInt());

        assertEquals(400, call("PUT", "/me/reviews/" + novelId, "{\"rating\":9}", first).status());

        assertEquals(204, call("DELETE", "/me/reviews/" + novelId, null, first).status());
        assertEquals(8.0, novelRepository.findById(novelId).orElseThrow().getRating(), 0.001);
        assertEquals(1, novelRepository.findById(novelId).orElseThrow().getRatingCount());
    }

    private Response register(String username, String email) {
        return call("POST", "/auth/register",
                "{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}",
                null);
    }

    private String login(String username, String email) {
        register(username, email);
        return token(call("POST", "/auth/login", json(new LoginRequest(email, PASSWORD)), null).setCookie());
    }

    /**
     * Creates a book with a unique title so repeated tests do not clash; the title is kept in {@link #lastTitle}
     */
    private Long newNovel(String title) {
        lastTitle = title + (++seq);
        Category category = categoryRepository.save(new Category("Cat" + seq, "", seq, "en"));
        Novel novel = new Novel();
        novel.setTitle(lastTitle);
        novel.setAuthor("author" + seq);
        novel.setCategory(category);
        novel.setChapterCount(5);
        novel.setCreatedAt(LocalDateTime.now());
        novel.setUpdatedAt(LocalDateTime.now());
        return novelRepository.save(novel).getId();
    }

    private Response call(String method, String path, String body, String token) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Accept", "application/json");
            if (body != null) {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            if (token != null) {
                builder.header("Cookie", "nb_session=" + token);
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String payload = response.body();
            JsonNode json = payload == null || payload.isBlank()
                    ? mapper.createObjectNode()
                    : mapper.readTree(payload);
            return new Response(response.statusCode(), json, response.headers().firstValue("Set-Cookie").orElse(null));
        } catch (Exception e) {
            throw new IllegalStateException("request failed: " + method + " " + path, e);
        }
    }

    private String json(Object body) {
        return mapper.writeValueAsString(body);
    }

    private record Response(int status, JsonNode body, String setCookie) {
    }

    private record LoginRequest(String email, String password) {
    }
}
