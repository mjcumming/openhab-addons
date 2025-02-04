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
package org.openhab.binding.honeywelltcc.internal.client;

import static org.openhab.binding.honeywelltcc.internal.HoneywellTCCBindingConstants.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import javax.net.ssl.SSLHandshakeException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.FormContentProvider;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.HoneywellTCCAuthException;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.HoneywellTCCException;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.HoneywellTCCInvalidParameterException;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.HoneywellTCCInvalidResponseException;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.HoneywellTCCRateLimitException;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.HoneywellTCCSessionExpiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A MyTotalConnectComfort HTTP client that follows openHAB design patterns.
 * Dependencies (e.g. HttpClient) are injected via the constructor.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class HoneywellTCCHttpClient implements AutoCloseable {
    private final Logger logger = LoggerFactory.getLogger(HoneywellTCCHttpClient.class);
    private final HttpClient httpClient;
    private final String username;
    private final String password;
    private final Gson gson;
    private final ScheduledExecutorService scheduler;
    private final CookieManager cookieManager;

    // Session state
    private boolean isAuthenticated = false;
    private long lastAuthTime = 0;
    private final Object sessionLock = new Object();
    private @Nullable ScheduledFuture<?> keepaliveJob;
    private final Map<String, String> cookies = new ConcurrentHashMap<>();
    private final Map<String, String> headers = new HashMap<>();

    private static final int MAX_RETRIES = HTTP_MAX_RETRIES;
    private static final long RETRY_DELAY_MS = HTTP_RETRY_DELAY_MS;
    private static final long SESSION_TIMEOUT_MS = HTTP_SESSION_TIMEOUT_MS;
    private static final int REQUEST_TIMEOUT_SEC = HTTP_REQUEST_TIMEOUT_SEC;

    // Track the current page for Referer header
    private String currentPage = "";

    // Define the cookie store and max cookie count
    private final List<HttpCookie> cookieStore = new ArrayList<>();
    private static final int MAX_COOKIE_COUNT = 5;

    public HoneywellTCCHttpClient(String username, String password, ScheduledExecutorService scheduler)
            throws HoneywellTCCException {
        this.username = username;
        this.password = password;
        this.scheduler = scheduler;
        this.gson = new Gson();
        this.cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

        // Create HttpClient with SSL support
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        sslContextFactory.setEndpointIdentificationAlgorithm("HTTPS");
        this.httpClient = new HttpClient(sslContextFactory);

        // Configure timeouts using constants
        this.httpClient.setConnectTimeout(HTTP_REQUEST_TIMEOUT_SEC * 1000L);
        this.httpClient.setIdleTimeout(HTTP_REQUEST_TIMEOUT_SEC * 1000L);

        // Configure client like Python requests
        this.httpClient.setFollowRedirects(true);
        this.httpClient.setUserAgentField(null);

        // Initialize headers exactly like Python
        initializeHeaders();
        startKeepalive();

        // Configure automatic cookie management
        this.httpClient.setCookieStore(cookieManager.getCookieStore());

        try {
            this.httpClient.start();
            logger.debug("Jetty HttpClient started with SSL support and automatic cookie management");
        } catch (Exception e) {
            throw new HoneywellTCCException("Failed to start HttpClient: " + e.getMessage(), e);
        }
    }

    /**
     * Initializes headers to match Python implementation exactly
     */
    private void initializeHeaders() {
        headers.clear();
        headers.put(HttpHeader.USER_AGENT.asString(), HEADER_USER_AGENT);
        headers.put(HttpHeader.ACCEPT.asString(), HEADER_ACCEPT);
        headers.put(HttpHeader.ACCEPT_LANGUAGE.asString(), HEADER_ACCEPT_LANGUAGE);
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put(HttpHeader.CONNECTION.asString(), HEADER_CONNECTION);
        updateRefererHeader(BASE_URL);

        logger.debug("Initialized headers: {}", headers);
    }

    /**
     * Updates Referer header based on current page
     * Matches Python's dynamic Referer handling
     */
    private void updateRefererHeader(String page) {
        currentPage = page;
        headers.put(HttpHeader.REFERER.asString(), currentPage);
        logger.debug("Updated Referer to: {}", currentPage);
    }

    private void startKeepalive() {
        synchronized (sessionLock) {
            if (keepaliveJob == null) {
                keepaliveJob = scheduler.scheduleWithFixedDelay(this::keepaliveTask, KEEPALIVE_INTERVAL_SEC,
                        KEEPALIVE_INTERVAL_SEC, TimeUnit.SECONDS);
            }
        }
    }

    private void keepaliveTask() {
        synchronized (sessionLock) {
            if (isAuthenticated) {
                keepalive().exceptionally(e -> {
                    logger.debug("Keepalive failed: {}", e.getMessage());
                    return null;
                });
            }
        }
    }

    /**
     * Keeps session alive (matches Python's keepalive())
     */
    public CompletableFuture<@Nullable Void> keepalive() {
        CompletableFuture<@Nullable Void> future = new CompletableFuture<>();
        String keepaliveUrl = BASE_URL + "/portal/KeepAlive"; // Adjust endpoint if different

        Request request = httpClient.newRequest(keepaliveUrl).method(HttpMethod.GET)
                .header(HttpHeader.REFERER.asString(), BASE_URL);

        logger.debug("Performing keepalive request");

        request.send(result -> {
            try {
                int status = result.getResponse().getStatus();
                logger.debug("Keepalive response status: {}", status);
                if (status == 200) {
                    logger.debug("Session refreshed successfully");
                    future.complete((Void) null);
                } else {
                    future.completeExceptionally(new HoneywellTCCException("Keepalive failed: status " + status));
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Executes request with retry logic (matches Python's _retries_login)
     */
    private <T> T executeWithRetry(String operation, RequestExecutor<T> executor) throws HoneywellTCCException {
        synchronized (sessionLock) {
            if (!isAuthenticated || (System.currentTimeMillis() - lastAuthTime) > SESSION_TIMEOUT_MS) {
                logger.debug("Session needs refresh, attempting keepalive");
                keepalive();
            }
        }

        try {
            return executor.execute();
        } catch (Exception e) {
            logger.info("Exception during request, retrying after login");
            login();
            return executor.execute();
        }
    }

    @Override
    public void close() {
        synchronized (sessionLock) {
            if (keepaliveJob != null) {
                keepaliveJob.cancel(true);
                keepaliveJob = null;
            }
        }
        try {
            httpClient.stop();
        } catch (Exception e) {
            logger.debug("Error closing HTTP client: {}", e.getMessage());
        }
    }

    /**
     * Logs into MyTotalConnectComfort by first doing a GET to establish cookies,
     * then a POST with form-encoded login data.
     *
     * @throws HoneywellTCCException if login fails.
     */

    public CompletableFuture<@Nullable Void> login() {
        CompletableFuture<@Nullable Void> future = new CompletableFuture<>();

        // Step 1: Initial GET to fetch login page and update cookies.
        httpClient.newRequest(BASE_URL).method(HttpMethod.GET).timeout(REQUEST_TIMEOUT_SEC, TimeUnit.SECONDS)
                .send(result -> {
                    try {
                        int getStatus = result.getResponse().getStatus();
                        logger.debug("Initial GET login page response status: {}", getStatus);
                        // Relying on Jetty's automatic cookie management; no manual cookie update needed.
                        // Proceed with POST login after GET completes.
                        performLoginPost(future);
                    } catch (Exception e) {
                        future.completeExceptionally(
                                new HoneywellTCCException("Initial GET for login failed: " + e.getMessage(), e));
                    }
                });
        return future;
    }

    // Updated performLoginPost() method to include additional headers and use the login endpoint constant.
    private void performLoginPost(CompletableFuture<@Nullable Void> future) {
        // Use the base URL as in the Python reference to post the login credentials.
        String loginUrl = BASE_URL;

        // Prepare login data as form fields (mimicking the Python payload)
        Fields loginData = new Fields();
        loginData.put(FORM_USERNAME, username);
        loginData.put(FORM_PASSWORD, password);
        loginData.put(FORM_REMEMBER_ME, "false");
        loginData.put(FORM_TIME_OFFSET, FORM_TIME_OFFSET_VALUE); // "480"

        Request request = httpClient.POST(loginUrl).header(HttpHeader.CONTENT_TYPE.asString(), CONTENT_TYPE_FORM)
                // Mimic Python client headers.
                .header(HttpHeader.USER_AGENT.asString(), HEADER_USER_AGENT)
                .header("X-Requested-With", "XMLHttpRequest").header(HttpHeader.REFERER.asString(), BASE_URL + "/")
                .followRedirects(true).content(new FormContentProvider(loginData));

        logger.debug("Attempting login at: {}", loginUrl);

        request.send(new BufferingResponseListener() {
            @Override
            public void onComplete(org.eclipse.jetty.client.api.Result result) {
                if (result.isSucceeded()) {
                    Response response = result.getResponse();
                    String content = getContentAsString();
                    if (content == null) {
                        future.completeExceptionally(new HoneywellTCCInvalidResponseException("Null response content"));
                        return;
                    }
                    int status = response.getStatus();
                    logger.debug("Login POST response status: {}", status);
                    try {
                        if (status == 200) {
                            if (content.contains("Invalid username or password")) {
                                future.completeExceptionally(
                                        new HoneywellTCCAuthException("Invalid username or password"));
                                return;
                            }
                            // Rely on Jetty's automatic cookie management.
                            logger.debug("Login complete; Jetty cookie store should now contain session cookies.");
                            // Call keepalive to validate the session immediately
                            keepalive().thenRun(() -> future.complete((Void) null)).exceptionally(e -> {
                                future.completeExceptionally(new HoneywellTCCException(
                                        "Keepalive after login failed: " + e.getMessage(), e));
                                return null;
                            });
                        } else {
                            future.completeExceptionally(new HoneywellTCCException("Login failed: status " + status));
                        }
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                } else {
                    future.completeExceptionally(result.getFailure());
                }
            }
        });
    }

    /**
     * Checks if the current session is authenticated.
     *
     * @return true if authenticated, false otherwise.
     */
    public boolean isAuthenticated() {
        return isAuthenticated;
    }

    /**
     * Retrieves thermostat data for the given device ID.
     * Matches Python's get_thermostat_data() implementation exactly.
     *
     * @param deviceId the device identifier
     * @return JsonObject containing thermostat data
     * @throws HoneywellTCCException if the request fails
     */
    public CompletableFuture<JsonObject> getThermostatData(String deviceId) {
        String url = String.format("%s/Device/CheckDataSession/%s", BASE_URL, deviceId);
        Request request = createRequest(url, HttpMethod.GET);
        return executeRequestAsync(request, "Get thermostat data").thenApply(response -> {
            try {
                JsonElement jsonResponse = handleResponse(response, "thermostat data");
                if (!jsonResponse.isJsonObject()) {
                    throw new HoneywellTCCInvalidResponseException("Expected JSON object response");
                }
                return jsonResponse.getAsJsonObject();
            } catch (HoneywellTCCException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Submits thermostat settings changes.
     * Matches Python's set_thermostat_settings() implementation exactly.
     *
     * @param deviceId the device identifier
     * @param settings JsonObject containing the settings to update
     * @throws HoneywellTCCException if the request fails
     */
    public CompletableFuture<Void> setThermostatSettings(String deviceId, JsonObject settings) {
        if (deviceId == null || settings == null) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(
                    new HoneywellTCCInvalidParameterException("Device ID and settings cannot be null"));
            return future;
        }

        JsonObject data = new JsonObject();
        data.addProperty("SystemSwitch", (String) null);
        data.addProperty("HeatSetpoint", (String) null);
        data.addProperty("CoolSetpoint", (String) null);
        data.addProperty("HeatNextPeriod", (String) null);
        data.addProperty("CoolNextPeriod", (String) null);
        data.addProperty("StatusHeat", (String) null);
        data.addProperty("DeviceID", deviceId);

        for (Map.Entry<String, JsonElement> entry : settings.entrySet()) {
            data.add(entry.getKey(), entry.getValue());
        }

        String url = BASE_URL + "/Device/SubmitControlScreenChanges";
        Request request = httpClient.POST(url).header(HttpHeader.CONTENT_TYPE.asString(), "application/json")
                .content(new StringContentProvider(gson.toJson(data)));

        return executeRequestAsync(request, "settings update").thenAccept(response -> {
            try {
                JsonElement jsonResponse = handleResponse(response, "thermostat settings");
                if (!jsonResponse.isJsonObject()) {
                    throw new HoneywellTCCInvalidResponseException("Expected JSON object response");
                }
                JsonObject result = jsonResponse.getAsJsonObject();
                if (result.get("success").getAsInt() != 1) {
                    throw new HoneywellTCCException("API rejected thermostat settings");
                }
            } catch (HoneywellTCCException e) {
                throw new CompletionException(e);
            }
        });
    }

    // Helper to convert JSON to form fields
    private Fields jsonToFormFields(JsonObject json) {
        Fields fields = new Fields();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            JsonElement value = entry.getValue();
            if (value != null && !value.isJsonNull()) {
                fields.put(entry.getKey(), value.toString());
            }
        }
        return fields;
    }

    /**
     * Retrieves the list of locations from the Honeywell TCC API.
     */
    public CompletableFuture<JsonObject> getLocations() {
        // 1. URL Construction - exact match to Python
        String url = BASE_URL + "/Location/GetLocationListData";

        // 2. Parameters - exact match to Python's params
        Request request = httpClient.POST(url).followRedirects(true) // Match Python's allow_redirects=True
                .param("page", "1") // Match Python's params={'page': 1, 'filter': ''}
                .param("filter", "");

        // 3. Headers - exact copy of Python's headers
        Map<String, String> headers = new HashMap<>(this.headers); // Copy base headers like Python
        headers.put(HttpHeader.REFERER.asString(), BASE_URL + "/");
        headers.forEach(request::header);

        // 4. Implement Python's _retries_login pattern
        return withRetries("get_locations", () -> {
            CompletableFuture<JsonObject> future = new CompletableFuture<>();

            // 5. Request execution with full logging like Python
            logger.debug("Sending request to {} with headers: {}", url, headers);
            logger.debug("Current cookies: {}", httpClient.getCookieStore().getCookies());

            // 6. Response handling with BufferingResponseListener to match Python's async with
            request.send(new BufferingResponseListener() {
                @Override
                public void onComplete(Result result) {
                    try {
                        if (result.isFailed()) {
                            handleRequestFailure(result.getFailure(), future);
                            return;
                        }

                        Response response = result.getResponse();
                        String content = getContentAsString();
                        if (content == null) {
                            future.completeExceptionally(
                                    new HoneywellTCCInvalidResponseException("Null response content"));
                            return;
                        }
                        int status = response.getStatus();

                        // 7. Match Python's exact response handling
                        logger.debug("Response status: {} headers: {}", status, response.getHeaders());
                        logger.debug("Response content: {}", content);

                        switch (status) {
                            case HttpStatus.OK_200:
                                handleSuccessResponse(response, content, future);
                                break;
                            case HttpStatus.FOUND_302:
                                handleRedirect(response, headers, future);
                                break;
                            case HttpStatus.TOO_MANY_REQUESTS_429:
                                future.completeExceptionally(new HoneywellTCCRateLimitException());
                                break;
                            case HttpStatus.UNAUTHORIZED_401:
                                handleUnauthorized(request, headers, future);
                                break;
                            default:
                                handleUnexpectedStatus(status, content, future);
                        }
                    } catch (Exception e) {
                        future.completeExceptionally(new HoneywellTCCException("Failed to process response", e));
                    }
                }
            });

            return future;
        });
    }

    // Helper methods to match Python's response handling
    @NonNullByDefault
    private void handleSuccessResponse(Response response, String content, CompletableFuture<JsonObject> future) {
        String contentType = Objects.requireNonNullElse(response.getHeaders().get(HttpHeader.CONTENT_TYPE.asString()),
                "no content type");

        // More lenient content type checking - just check if it contains application/json
        if (contentType.toLowerCase().contains("application/json")) {
            try {
                JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                future.complete(json);
            } catch (Exception e) {
                future.completeExceptionally(new HoneywellTCCInvalidResponseException("Invalid JSON response"));
            }
        } else {
            future.completeExceptionally(new HoneywellTCCInvalidResponseException(
                    String.format("Expected JSON response, got: %s", contentType)));
        }
    }

    @NonNullByDefault
    private void handleRedirect(Response response, Map<String, String> headers, CompletableFuture<JsonObject> future) {
        String location = getLocation(response);

        if (location.isEmpty()) {
            future.completeExceptionally(new HoneywellTCCException("Missing redirect location header"));
            return;
        }

        logger.debug("Following redirect to: {}", location);

        Request redirectRequest = httpClient.POST(location);
        headers.forEach(redirectRequest::header);

        redirectRequest.send(new BufferingResponseListener() {
            @Override
            public void onComplete(Result redirectResult) {
                if (redirectResult.isSucceeded()) {
                    String content = getContentAsString();
                    if (content != null) {
                        handleSuccessResponse(redirectResult.getResponse(), content, future);
                    } else {
                        future.completeExceptionally(new HoneywellTCCInvalidResponseException("Null response content"));
                    }
                } else {
                    // OpenHAB pattern: Safe error message construction
                    String errorMessage = redirectResult.getFailure() != null ? redirectResult.getFailure().toString()
                            : "Unknown error";
                    future.completeExceptionally(new HoneywellTCCException("Redirect failed: " + errorMessage));
                }
            }
        });
    }

    private void handleUnauthorized(Request originalRequest, Map<String, String> headers,
            CompletableFuture<JsonObject> future) {
        // Handle 401 without WWW-Authenticate header
        logger.debug("Session expired, attempting re-authentication");
        login().thenCompose(v -> {
            Request retryRequest = httpClient.POST(originalRequest.getURI());
            headers.forEach(retryRequest::header);
            return executeRequestAsync(retryRequest, "retry after auth");
        }).thenAccept(response -> {
            try {
                handleSuccessResponse(response, ((ContentResponse) response).getContentAsString(), future);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }).exceptionally(e -> {
            future.completeExceptionally(new HoneywellTCCException("Re-authentication failed", e));
            return null;
        });
    }

    public long getLastAuthTime() {
        return lastAuthTime;
    }

    /**
     * Handles HTTP response like Python's _request_json
     */
    private JsonElement handleResponse(ContentResponse response, String operation) throws HoneywellTCCException {
        String content = response.getContentAsString();
        int status = response.getStatus();

        // Log everything about the response
        logger.debug("Response status: {}", status);
        logger.debug("Response headers: {}", response.getHeaders());
        logger.debug("Response content: {}", content);
        logger.debug("Response cookies: {}", httpClient.getCookieStore().getCookies());

        if (status == HttpStatus.OK_200) {
            String contentType = getContentType(response);
            if (contentType.contains("application/json")) {
                try {
                    JsonElement result = JsonParser.parseString(content);
                    logger.debug("Parsed JSON result: {}", result);
                    return result;
                } catch (Exception e) {
                    logger.error("Failed to parse JSON response: {}", content);
                    throw new HoneywellTCCInvalidResponseException("Invalid JSON response");
                }
            } else {
                logger.error("Unexpected response type: {}", contentType);
                logger.error("Response text: {}", content);
                throw new HoneywellTCCInvalidResponseException(
                        String.format("Unexpected response type: %s, Content: %s", contentType, content));
            }
        } else if (status == HttpStatus.FOUND_302) {
            // Handle redirect explicitly
            String location = getLocation(response);
            logger.debug("Following redirect to: {}", location);
            // Since we're using followRedirects(true), this shouldn't happen
            throw new HoneywellTCCException("Unexpected redirect to: " + location);
        } else if (status == 429) {
            logger.error("Rate limit exceeded: {}", status);
            logger.error("Response text: {}", content);
            throw new HoneywellTCCRateLimitException("You are being rate-limited. Try waiting a bit.");
        } else if (status == HttpStatus.UNAUTHORIZED_401) {
            throw new HoneywellTCCSessionExpiredException("Session has timed out.");
        } else {
            logger.error("API returned {} from {} request", status, operation);
            logger.error("Response body: {}", content);
            throw new HoneywellTCCException(String.format("Unexpected %d response from API: %s...", status,
                    content.substring(0, Math.min(content.length(), 200))));
        }
    }

    /**
     * Creates a new request with proper headers (without manually adding cookies).
     * Jetty's HttpClient will automatically add stored cookies.
     */
    private Request createRequest(String url, HttpMethod method) {
        Request request = httpClient.newRequest(url).method(method).timeout(REQUEST_TIMEOUT_SEC, TimeUnit.SECONDS);
        addHeaders(request);
        return request;
    }

    @FunctionalInterface
    private interface RequestExecutor<T> {
        T execute() throws HoneywellTCCException;
    }

    /**
     * Creates a new request with the given URL.
     * 
     * @param url the URL to request
     * @return the created request
     */
    public Request newRequest(String url) {
        Request request = httpClient.newRequest(url);
        request.timeout(REQUEST_TIMEOUT_SEC, TimeUnit.SECONDS);
        return request;
    }

    /**
     * Creates a new POST request.
     * 
     * @param url the URL to POST to
     * @return the created request
     */
    public Request POST(String url) {
        return newRequest(url).method(HttpMethod.POST);
    }

    private void handleException(String operation, Exception e) throws HoneywellTCCException {
        String message = e.getMessage();
        if (message == null) {
            message = e.getClass().getSimpleName();
        }

        if (e instanceof UnknownHostException) {
            throw new HoneywellTCCException("Host not found: " + message, HttpStatus.NOT_FOUND_404);
        } else if (e instanceof SSLHandshakeException) {
            throw new HoneywellTCCException("SSL handshake failed: " + message, HttpStatus.BAD_REQUEST_400);
        } else if (e instanceof TimeoutException) {
            throw new HoneywellTCCException("Request timed out: " + message, HttpStatus.REQUEST_TIMEOUT_408);
        } else if (e instanceof ExecutionException) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                handleException(operation, (Exception) cause);
            } else {
                throw new HoneywellTCCException("Request failed: " + message, HttpStatus.INTERNAL_SERVER_ERROR_500);
            }
        } else if (e instanceof HttpResponseException) {
            HttpResponseException hre = (HttpResponseException) e;
            int status = hre.getResponse().getStatus();
            throw new HoneywellTCCException("Invalid response: " + message, status);
        } else {
            throw new HoneywellTCCException("Request failed: " + message, HttpStatus.INTERNAL_SERVER_ERROR_500);
        }
    }

    private String executeRequest(String url, HttpMethod method, @Nullable String body) throws HoneywellTCCException {
        try {
            Request request = createRequest(url, method);
            if (body != null) {
                request.content(new StringContentProvider(body));
            }

            ContentResponse response = request.send();
            return handleResponse(response, method + " " + url).toString();
        } catch (Exception e) {
            handleException(method + " " + url, e);
            return ""; // Never reached, handleException always throws
        }
    }

    private void checkStatusCodes(ContentResponse response) throws HoneywellTCCException {
        int status = response.getStatus();
        String content = response.getContentAsString();

        switch (status) {
            case HttpStatus.OK_200:
                return;
            case HttpStatus.UNAUTHORIZED_401:
            case HttpStatus.FORBIDDEN_403:
                isAuthenticated = false;
                throw new HoneywellTCCSessionExpiredException("Session expired", status);
            case HttpStatus.TOO_MANY_REQUESTS_429:
                throw new HoneywellTCCRateLimitException("Rate limit exceeded", status);
            case HttpStatus.BAD_REQUEST_400:
                throw new HoneywellTCCException("Bad request", status);
            case HttpStatus.INTERNAL_SERVER_ERROR_500:
                throw new HoneywellTCCException("Server error", status);
            case HttpStatus.BAD_GATEWAY_502:
            case HttpStatus.SERVICE_UNAVAILABLE_503:
            case HttpStatus.GATEWAY_TIMEOUT_504:
                throw new HoneywellTCCException("Service unavailable", status);
            default:
                throw new HoneywellTCCException("HTTP error " + status + ": " + content, status);
        }
    }

    private JsonElement getJson(String url) throws HoneywellTCCException {
        Request request = createRequest(url, HttpMethod.GET);
        ContentResponse response = executeRequest(request, "GET " + url);
        return handleResponse(response, "GET " + url);
    }

    private JsonElement postJson(String url, Fields formData) throws HoneywellTCCException {
        Request request = createRequest(url, HttpMethod.POST).content(new FormContentProvider(formData));
        ContentResponse response = executeRequest(request, "POST " + url);
        return handleResponse(response, "POST " + url);
    }

    /**
     * Executes the given HTTP request asynchronously using a BufferingResponseListener,
     * converting the callback into a CompletableFuture.
     *
     * @param request the HTTP request to execute
     * @param operation a label for logging or error messages
     * @return a CompletableFuture that will complete with the ContentResponse
     */
    private CompletableFuture<ContentResponse> executeRequestAsync(Request request, String operation) {
        long startTime = System.currentTimeMillis();
        CompletableFuture<ContentResponse> future = new CompletableFuture<>();

        request.send(new BufferingResponseListener() {
            @Override
            public void onComplete(org.eclipse.jetty.client.api.Result result) {
                long duration = System.currentTimeMillis() - startTime;
                if (result.isSucceeded()) {
                    logger.debug("{} completed in {} ms", operation, duration);
                    future.complete((ContentResponse) this);
                } else {
                    logger.error("{} failed after {} ms: {}", operation, duration, result.getFailure().getMessage());
                    future.completeExceptionally(result.getFailure());
                }
            }
        });
        return future;
    }

    private void updateSessionState(ContentResponse response) {
        synchronized (sessionLock) {
            if (response.getStatus() == HttpStatus.OK_200) {
                isAuthenticated = true;
                lastAuthTime = System.currentTimeMillis();
                logger.debug("Session authenticated");
            } else {
                isAuthenticated = false;
                logger.debug("Session invalidated");
            }
        }
    }

    /**
     * Adds current headers (except Cookie) to the request.
     * Remove manual cookie handling since Jetty does that automatically.
     */
    private void addHeaders(Request request) {
        request.header(HttpHeader.ACCEPT.asString(),
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
        request.header("X-Requested-With", "XMLHttpRequest");
        request.header(HttpHeader.USER_AGENT.asString(),
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
        request.header(HttpHeader.CONNECTION.asString(), "keep-alive");
        request.header(HttpHeader.REFERER.asString(), "https://www.mytotalconnectcomfort.com/portal");
        request.header(HttpHeader.ACCEPT_LANGUAGE.asString(), "en-US,en;q=0.9");

        // Do not add any Cookie header here since HttpClient automatically adds cookies.
        logger.debug("Added headers to request (excluding Cookie): {}", request.getHeaders());
    }

    /**
     * Set the thermostat mode
     */
    public void setMode(String deviceId, String locationId, String mode) throws HoneywellTCCException {
        Map<String, Object> data = new HashMap<>();
        data.put("SystemSwitch", mode);
        submitControlChanges(deviceId, locationId, data);
    }

    /**
     * Set the fan mode
     */
    public void setFanMode(String deviceId, String locationId, String fanMode) throws HoneywellTCCException {
        Map<String, Object> data = new HashMap<>();
        data.put("FanMode", fanMode);
        submitControlChanges(deviceId, locationId, data);
    }

    /**
     * Set the heat setpoint
     */
    public void setHeatSetpoint(String deviceId, String locationId, double temperature) throws HoneywellTCCException {
        Map<String, Object> data = new HashMap<>();
        data.put("HeatSetpoint", temperature);
        submitControlChanges(deviceId, locationId, data);
    }

    /**
     * Set the cool setpoint
     */
    public void setCoolSetpoint(String deviceId, String locationId, double temperature) throws HoneywellTCCException {
        Map<String, Object> data = new HashMap<>();
        data.put("CoolSetpoint", temperature);
        submitControlChanges(deviceId, locationId, data);
    }

    private void submitControlChanges(String deviceId, String locationId, Map<String, Object> data)
            throws HoneywellTCCException {
        // Add required fields
        data.put("DeviceID", deviceId);
        data.put("LocationID", locationId);

        try {
            String url = BASE_URL + "/portal/Device/SubmitControlScreenChanges";
            Request request = httpClient.POST(url).header(HttpHeader.CONTENT_TYPE.asString(), "application/json")
                    .content(new StringContentProvider(gson.toJson(data)));

            executeRequest(request, "submit control changes");
        } catch (Exception e) {
            throw new HoneywellTCCException("Failed to submit control changes: " + e.getMessage(), e);
        }
    }

    /**
     * Executes the given HTTP request synchronously using the provided Request and returns
     * the ContentResponse after updating session state and checking the response.
     *
     * @param request the HTTP request to execute
     * @param operation a label for logging or error messages
     * @return the ContentResponse from the HTTP request
     * @throws HoneywellTCCException if the request fails or returns an unexpected response
     */
    private ContentResponse executeRequest(Request request, String operation) throws HoneywellTCCException {
        try {
            ContentResponse response = request.send();
            updateSessionState(response);
            checkStatusCodes(response);
            return response;
        } catch (Exception e) {
            handleException(operation, e);
            // This line will never be reached since handleException always throws.
            throw new HoneywellTCCException("Unreachable", e);
        }
    }

    private void validateJsonResponse(ContentResponse response, String operation)
            throws HoneywellTCCInvalidResponseException {
        String contentType = getContentType(response);
        if (!contentType.contains("application/json")) {
            String responseBody = response.getContentAsString();
            logger.error("Unexpected content type for {}: {}", operation, contentType);
            logger.error("Response body: {}", responseBody);
            throw new HoneywellTCCInvalidResponseException(
                    String.format("Expected JSON response for %s, got: %s", operation, contentType));
        }
    }

    private boolean sessionExpired() {
        synchronized (sessionLock) {
            boolean expired = !isAuthenticated || (System.currentTimeMillis() - lastAuthTime > SESSION_TIMEOUT_MS);
            if (expired) {
                logger.debug("Session expired - authenticated: {}, last auth: {} ms ago", isAuthenticated,
                        System.currentTimeMillis() - lastAuthTime);
            }
            return expired;
        }
    }

    // Add retries wrapper similar to Python
    private <T> CompletableFuture<T> withRetries(String operation, Supplier<CompletableFuture<T>> action) {
        return keepalive().thenCompose(v -> action.get()).exceptionally(ex -> {
            if (ex instanceof HoneywellTCCSessionExpiredException) {
                return login().thenCompose(v -> action.get()).join();
            }
            throw new CompletionException(ex);
        });
    }

    private void logCookieState(String operation) {
        httpClient.getCookieStore().getCookies().forEach(cookie -> logger.debug("{} cookie: {}={} (domain={}, path={})",
                operation, cookie.getName(), cookie.getValue(), cookie.getDomain(), cookie.getPath()));
    }

    private void handleRequestFailure(Throwable failure, CompletableFuture<JsonObject> future) {
        logger.error("Request failed: {}", failure.getMessage());
        future.completeExceptionally(new HoneywellTCCException("Request failed", failure));
    }

    private void handleUnexpectedStatus(int status, @org.eclipse.jdt.annotation.NonNull String content,
            @org.eclipse.jdt.annotation.NonNull CompletableFuture<JsonObject> future) {
        logger.error("API returned {} from request", status);
        logger.error("Response body: {}", content);
        future.completeExceptionally(new HoneywellTCCException("Unexpected " + status + " response from API"));
    }

    // For handling content type headers
    @org.eclipse.jdt.annotation.NonNull
    private String getContentType(@org.eclipse.jdt.annotation.NonNull Response response) {
        String contentType = response.getHeaders().get(HttpHeader.CONTENT_TYPE);
        return contentType != null ? contentType : "application/octet-stream";
    }

    // For handling location headers
    @org.eclipse.jdt.annotation.NonNull
    private String getLocation(@org.eclipse.jdt.annotation.NonNull Response response) {
        String location = response.getHeaders().get(HttpHeader.LOCATION);
        return location != null ? location : "";
    }
}
