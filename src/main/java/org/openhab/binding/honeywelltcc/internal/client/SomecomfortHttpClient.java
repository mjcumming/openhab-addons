/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.somecomfort.internal.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.openhab.binding.somecomfort.internal.client.exceptions.*;

/**
 * The {@link SomecomfortHttpClient} class handles low-level HTTP communication with the Total Comfort API.
 * It manages authentication, session handling, and raw API requests.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class SomecomfortHttpClient {
    private static final Logger logger = LoggerFactory.getLogger(SomecomfortHttpClient.class);
    private static final String BASE_URL = "https://www.mytotalconnectcomfort.com/portal";
    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_RETRIES = 3;

    private final HttpClient httpClient;
    private final Gson gson;
    private final String username;
    private final String password;
    private final Map<String, String> cookies = new ConcurrentHashMap<>();

    private @Nullable String defaultUrl;
    private boolean authenticated;

    /**
     * Creates a new HTTP client instance
     *
     * @param username Total Comfort account username
     * @param password Total Comfort account password
     */
    public SomecomfortHttpClient(String username, String password) {
        this.username = username;
        this.password = password;
        this.defaultUrl = BASE_URL;
        this.authenticated = false;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
        this.gson = new Gson();
    }

    /**
     * Logs in to the Total Comfort API
     *
     * @throws SomeComfortError if login fails
     */
    public synchronized void login() throws SomeComfortError {
        try {
            // Initial GET to get cookies
            HttpRequest initialRequest = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            HttpResponse<String> initialResponse = httpClient.send(initialRequest, HttpResponse.BodyHandlers.ofString());
            updateCookies(initialResponse);

            // Login POST
            Map<String, String> formData = new HashMap<>();
            formData.put("UserName", username);
            formData.put("Password", password);
            formData.put("RememberMe", "false");
            formData.put("timeOffset", "480");

            HttpRequest loginRequest = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .POST(buildFormDataFromMap(formData))
                    .build();

            HttpResponse<String> loginResponse = httpClient.send(loginRequest, HttpResponse.BodyHandlers.ofString());

            if (loginResponse.statusCode() != 200) {
                throw new AuthError("Login failed with status code: " + loginResponse.statusCode());
            }

            String body = loginResponse.body();
            if (body.contains("Invalid username or password")) {
                throw new AuthError("Invalid username or password");
            }

            updateCookies(loginResponse);
            defaultUrl = loginResponse.uri().toString();

            // Verify login with keepalive
            try {
                keepalive();
            } catch (SessionError e) {
                throw new AuthError("Login failed during keepalive check");
            }

            authenticated = true;
            logger.debug("Login successful");

        } catch (IOException | InterruptedException e) {
            throw new SomeComfortError("Login failed: " + e.getMessage());
        }
    }

    /**
     * Performs a keepalive check to verify the session is still valid
     *
     * @throws SomeComfortError if the session has expired
     */
    public void keepalive() throws SomeComfortError {
        try {
            String url = defaultUrl != null ? defaultUrl : BASE_URL;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.debug("Session timed out");
                throw new SessionError("Session timed out");
            }
            logger.debug("Session refreshed");
            updateCookies(response);
        } catch (IOException | InterruptedException e) {
            throw new SomeComfortError("Keepalive failed: " + e.getMessage());
        }
    }

    /**
     * Performs a GET request to the API
     *
     * @param path the API path
     * @return JsonObject containing the response
     * @throws SomeComfortError if the request fails
     */
    public JsonObject get(String path) throws SomeComfortError {
        return requestJson("GET", BASE_URL + path, null, null);
    }

    /**
     * Performs a POST request to the API
     *
     * @param path the API path
     * @param params query parameters
     * @param body request body
     * @return JsonObject containing the response
     * @throws SomeComfortError if the request fails
     */
    public JsonObject post(String path, @Nullable Map<String, String> params, @Nullable String body)
            throws SomeComfortError {
        return requestJson("POST", BASE_URL + path, params, body);
    }

    private JsonObject requestJson(String method, String url, @Nullable Map<String, String> params, @Nullable String body)
            throws SomeComfortError {
        checkAuthentication();

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .header("User-Agent", "Mozilla/5.0")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01");

            if (body != null) {
                builder.header("Content-Type", "application/json");
                builder.POST(HttpRequest.BodyPublishers.ofString(body));
            } else if ("POST".equals(method)) {
                String formData = buildQueryString(params);
                builder.header("Content-Type", "application/x-www-form-urlencoded");
                builder.POST(HttpRequest.BodyPublishers.ofString(formData));
            } else {
                if (params != null && !params.isEmpty()) {
                    url += "?" + buildQueryString(params);
                }
                builder.GET();
            }

            // Add cookies to request
            if (!cookies.isEmpty()) {
                StringBuilder cookieHeader = new StringBuilder();
                cookies.forEach((name, value) -> {
                    if (cookieHeader.length() > 0) {
                        cookieHeader.append("; ");
                    }
                    cookieHeader.append(name).append("=").append(value);
                });
                builder.header("Cookie", cookieHeader.toString());
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            updateCookies(response);

            if (response.statusCode() == 200) {
                try {
                    return gson.fromJson(response.body(), JsonObject.class);
                } catch (Exception e) {
                    logger.error("Failed to parse JSON response", e);
                    throw new APIError("Failed to process response");
                }
            } else if (response.statusCode() == 401) {
                throw new RateLimitError("API rate limit exceeded");
            } else if (response.statusCode() == 403) {
                authenticated = false;
                throw new SessionError("Session expired");
            } else {
                logger.error("API returned {} from request to {}", response.statusCode(), url);
                throw new APIError("Unexpected " + response.statusCode() + " response from API");
            }
        } catch (IOException | InterruptedException e) {
            throw new SomeComfortError("Request failed: " + e.getMessage());
        }
    }

    private void checkAuthentication() throws AuthError {
        if (!authenticated) {
            throw new AuthError("Not authenticated");
        }
    }

    private String buildQueryString(@Nullable Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (result.length() > 0) {
                result.append("&");
            }
            result.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return result.toString();
    }

    private HttpRequest.BodyPublisher buildFormDataFromMap(Map<String, String> data) {
        return HttpRequest.BodyPublishers.ofString(buildQueryString(data));
    }

    private void updateCookies(HttpResponse<?> response) {
        response.headers().allValues("Set-Cookie").forEach(cookie -> {
            String[] parts = cookie.split(";")[0].split("=");
            if (parts.length == 2) {
                cookies.put(parts[0].trim(), parts[1].trim());
            }
        });
    }

    /**
     * Closes the HTTP client and clears session data
     */
    public void close() {
        authenticated = false;
        cookies.clear();
        defaultUrl = null;
    }
} 