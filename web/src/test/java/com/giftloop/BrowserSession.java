package com.giftloop;

import io.restassured.filter.cookie.CookieFilter;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;

/**
 * Test helper acting like a browser: keeps cookies (session + CSRF), sends HTMX
 * requests, and fills in the CSRF token scraped from rendered pages.
 */
public final class BrowserSession {

    private static final Pattern CSRF = Pattern.compile("name=\"csrf_token\" value=\"([^\"]+)\"");

    private final CookieFilter cookies = new CookieFilter();
    private String csrfToken;

    /**
     * Request spec sharing this session's cookies; redirects are not followed.
     *
     * @return spec
     */
    public RequestSpecification request() {
        return given().filter(cookies).redirects().follow(false);
    }

    /**
     * Full-page GET; remembers the CSRF token if the page has one.
     *
     * @param path path
     * @return response
     */
    public Response get(String path) {
        Response response = request().get(path);
        remember(response.asString());
        return response;
    }

    /**
     * HTMX form POST with the CSRF token added.
     *
     * @param path path
     * @param form form fields
     * @return response
     */
    public Response htmxPost(String path, Map<String, ?> form) {
        ensureCsrf();
        Response response = request().header("HX-Request", "true").formParams(form)
                .formParam("csrf_token", csrfToken).post(path);
        remember(response.asString());
        return response;
    }

    /**
     * HTMX multipart POST (profile forms) with the CSRF token added.
     *
     * @param path      path
     * @param fields    text fields
     * @param fileBytes avatar bytes, or null for no file
     * @param mimeType  avatar MIME type
     * @return response
     */
    public Response htmxMultipart(String path, Map<String, String> fields, byte[] fileBytes, String mimeType) {
        ensureCsrf();
        RequestSpecification spec = request().header("HX-Request", "true").multiPart("csrf_token", csrfToken);
        fields.forEach(spec::multiPart);
        if (fileBytes != null) {
            spec.multiPart("avatar", "photo", fileBytes, mimeType);
        }
        Response response = spec.post(path);
        remember(response.asString());
        return response;
    }

    /**
     * Signs in with email and password through the real pages.
     *
     * @param email    email
     * @param password password
     * @return the sign-in response
     */
    public Response signInWithPassword(String email, String password) {
        get("/signin");
        return htmxPost("/signin/password", Map.of("email", email, "password", password, "next", "/"));
    }

    /**
     * Creates a password account through the real pages (signs in as it).
     *
     * @param email    email
     * @param password password (at least 8 characters)
     * @return the sign-up response
     */
    public Response signUp(String email, String password) {
        get("/signup");
        return htmxPost("/signup", Map.of("email", email, "password", password, "next", "/"));
    }

    /**
     * Current CSRF token (loads {@code /signin} to obtain one if needed).
     *
     * @return token
     */
    public String csrfToken() {
        ensureCsrf();
        return csrfToken;
    }

    private void ensureCsrf() {
        if (csrfToken == null) {
            get("/signin");
        }
        if (csrfToken == null) {
            throw new IllegalStateException("No CSRF token found on /signin");
        }
    }

    private void remember(String body) {
        Matcher m = CSRF.matcher(body);
        if (m.find()) {
            csrfToken = m.group(1);
        }
    }
}
