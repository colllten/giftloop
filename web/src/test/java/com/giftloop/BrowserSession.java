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
        return htmxMultipart(path, fields, "avatar", fileBytes, mimeType);
    }

    /**
     * HTMX multipart POST with the CSRF token added. List values become repeated fields.
     *
     * @param path      path
     * @param fields    text fields (String or List of String values)
     * @param fileField name of the file field
     * @param fileBytes file bytes, or null for no file
     * @param mimeType  file MIME type
     * @return response
     */
    public Response htmxMultipart(String path, Map<String, ?> fields, String fileField, byte[] fileBytes,
                                  String mimeType) {
        ensureCsrf();
        RequestSpecification spec = request().header("HX-Request", "true").multiPart("csrf_token", csrfToken);
        fields.forEach((name, value) -> {
            if (value instanceof java.util.List<?> list) {
                list.forEach(v -> spec.multiPart(name, String.valueOf(v)));
            } else {
                spec.multiPart(name, String.valueOf(value));
            }
        });
        if (fileBytes != null) {
            spec.multiPart(fileField, "photo", fileBytes, mimeType);
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
     * Creates a password account through the real pages (signs in as it), reading the
     * emailed code from Mailpit.
     *
     * @param email    email
     * @param password password (at least 8 characters)
     * @return the final sign-up response
     */
    public Response signUp(String email, String password) {
        requestSignUpCode(email);
        return htmxPost("/signup/verify",
                Map.of("email", email, "code", Mailpit.latestCode(email), "password", password, "next", "/"));
    }

    /**
     * First sign-up step: asks for a code to be emailed.
     *
     * @param email email
     * @return the code step response
     */
    public Response requestSignUpCode(String email) {
        get("/signup");
        return htmxPost("/signup", Map.of("email", email, "next", "/"));
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
