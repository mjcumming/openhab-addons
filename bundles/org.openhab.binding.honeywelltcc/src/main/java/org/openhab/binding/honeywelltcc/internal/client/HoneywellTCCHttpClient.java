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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import javax.net.ssl.SSLHandshakeException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.FormContentProvider;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.Fields;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.HoneywellTCCAuthException;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.HoneywellTCCException;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.HoneywellTCCInvalidParameterException;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.HoneywellTCCInvalidResponseException;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.HoneywellTCCRateLimitException;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.HoneywellTCCSessionExpiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
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

    public static HoneywellTCCHttpClient create(HttpClient httpClient, String username, String password,
            ScheduledExecutorService scheduler) throws HoneywellTCCException {
        return new HoneywellTCCHttpClient(httpClient, username, password, scheduler);
    }

    private HoneywellTCCHttpClient(HttpClient httpClient, String username, String password,
            ScheduledExecutorService scheduler) throws HoneywellTCCException {
        this.httpClient = httpClient;
        this.username = username;
        this.password = password;
        this.scheduler = scheduler;
        this.gson = new Gson();
        this.cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

        // Configure timeouts using constants
        httpClient.setConnectTimeout(HTTP_REQUEST_TIMEOUT_SEC * 1000L);
        httpClient.setIdleTimeout(HTTP_REQUEST_TIMEOUT_SEC * 1000L);

        // Configure client like Python requests
        this.httpClient.setFollowRedirects(true);
        this.httpClient.setUserAgentField(null);

        // Initialize headers exactly like Python
        initializeHeaders();
        startKeepalive();

        // Configure automatic cookie management
        httpClient.setCookieStore(cookieManager.getCookieStore());

        try {
            httpClient.start();
            logger.debug("Jetty HttpClient started with automatic cookie management");
        } catch (Exception e) {
            throw new HoneywellTCCException("Failed to start HttpClient", e);
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
                    updateCookiesFromResponse(result.getResponse());
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
                        updateCookiesFromResponse(result.getResponse());
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
                    int status = response.getStatus();
                    logger.debug("Login POST response status: {}", status);
                    // Retrieve buffered response as UTF-8 content
                    String responseContent = new String(getContent(), StandardCharsets.UTF_8);
                    try {
                        if (status == 200) {
                            if (responseContent.contains("Invalid username or password")) {
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
    public JsonObject getThermostatData(String deviceId) throws HoneywellTCCException {
        return executeWithRetry("Get thermostat data", () -> {
            String url = String.format("%s/Device/CheckDataSession/%s", BASE_URL, deviceId);
            Request request = createRequest(url, HttpMethod.GET);
            ContentResponse response = executeRequest(request, "get thermostat data");
            JsonElement jsonResponse = handleResponse(response, "thermostat data");
            if (!jsonResponse.isJsonObject()) {
                throw new HoneywellTCCInvalidResponseException("Expected JSON object response");
            }
            return jsonResponse.getAsJsonObject();
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
    public void setThermostatSettings(String deviceId, JsonObject settings) throws HoneywellTCCException {
        if (deviceId == null || settings == null) {
            throw new HoneywellTCCInvalidParameterException("Device ID and settings cannot be null");
        }

        executeWithRetry("Set thermostat settings", () -> {
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
            Request request = createRequest(url, HttpMethod.POST)
                    .content(new FormContentProvider(jsonToFormFields(data)));

            ContentResponse response = executeRequest(request, "settings update");
            JsonElement jsonResponse = handleResponse(response, "thermostat settings");
            if (!jsonResponse.isJsonObject()) {
                throw new HoneywellTCCInvalidResponseException("Expected JSON object response");
            }

            JsonObject result = jsonResponse.getAsJsonObject();
            if (result.get("success").getAsInt() != 1) {
                throw new HoneywellTCCException("API rejected thermostat settings");
            }
            return null;
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
     * Gets the list of locations and their devices from TCC
     * 
     * @return JsonArray of locations containing devices
     * @throws HoneywellTCCException if request fails
     */
    public JsonArray getLocations() throws HoneywellTCCException {
        return executeWithRetry("Get locations", () -> {
            String url = BASE_URL + ENDPOINT_LOCATIONS;

            // Prepare form parameters as per Python reference.
            Fields formFields = new Fields();
            formFields.put(PARAM_PAGE, PARAM_PAGE_VALUE);
            formFields.put(PARAM_FILTER, PARAM_FILTER_VALUE);

            // Build the POST request with the expected headers.
            Request request = httpClient.POST(url).header(HttpHeader.CONTENT_TYPE.asString(), CONTENT_TYPE_FORM)
                    .header(HttpHeader.REFERER.asString(), BASE_URL + "/")
                    .header(HttpHeader.USER_AGENT.asString(), HEADER_USER_AGENT)
                    .header("X-Requested-With", "XMLHttpRequest").header(HttpHeader.ACCEPT.asString(), HEADER_ACCEPT)
                    .header(HttpHeader.ACCEPT_LANGUAGE.asString(), HEADER_ACCEPT_LANGUAGE)
                    .header(HttpHeader.CONNECTION.asString(), HEADER_CONNECTION).followRedirects(true);

            // Send the request and process the response.
            ContentResponse response = executeRequest(request, "locations");
            JsonElement jsonResponse = handleResponse(response, "locations");
            if (!jsonResponse.isJsonObject()) {
                throw new HoneywellTCCInvalidResponseException("Expected JSON object response");
            }
            // Extract as needed – assuming the JSON object contains an array under RESPONSE_LOCATIONS.
            JsonObject result = jsonResponse.getAsJsonObject();
            return result.getAsJsonArray(RESPONSE_LOCATIONS);
        });
    }

    public long getLastAuthTime() {
        return lastAuthTime;
    }

    /**
     * Updates the internal cookie store using cookies received from an HTTP response.
     * This method ensures that duplicate entries are overwritten and skips (or removes)
     * any cookies with invalid name-value pairs.
     * 
     * This method accepts a collection of HttpCookie objects.
     */
    private void updateCookies(Collection<HttpCookie> responseCookies) {
        // Create a map to hold the unique, valid cookies by name.
        Map<String, HttpCookie> cookiesMap = new HashMap<>();

        // Add any already stored cookies that are valid.
        for (HttpCookie cookie : cookieStore) {
            if (isValidCookie(cookie)) {
                cookiesMap.put(cookie.getName(), cookie);
            }
        }

        // Process new cookies received from the response.
        for (HttpCookie newCookie : responseCookies) {
            try {
                if (isValidCookie(newCookie)) {
                    cookiesMap.put(newCookie.getName(), newCookie);
                } else {
                    // If the cookie is not valid (e.g., empty value), remove any previously stored cookie.
                    cookiesMap.remove(newCookie.getName());
                    logger.debug("Skipping or removing invalid/empty cookie: {}", newCookie);
                }
            } catch (IllegalArgumentException e) {
                // In case parsing a cookie throws an exception, skip this one.
                logger.warn("Skipping invalid cookie {}: {}", newCookie, e.getMessage());
            }
        }

        // Replace the current cookie store with the validated cookies.
        cookieStore.clear();
        cookieStore.addAll(cookiesMap.values());

        // Reconstruct the Cookie header from the unique, valid cookies.
        String cookieHeader = cookiesMap.values().stream().map(cookie -> cookie.getName() + "=" + cookie.getValue())
                .collect(Collectors.joining("; "));

        // Update the request headers for subsequent requests.
        headers.put(HttpHeader.COOKIE.asString(), cookieHeader);
        logger.debug("Updated cookie header: {}", cookieHeader);
    }

    /**
     * Updates the internal cookie store using cookie header strings.
     * It parses each header into HttpCookie objects and delegates to the primary updateCookies method.
     * 
     * Note: Callers previously using updateCookies(passCollectionOfStrings) should use this method instead.
     */
    private void updateCookiesFromHeaders(Collection<String> cookieHeaders) {
        List<HttpCookie> cookies = new ArrayList<>();
        for (String header : cookieHeaders) {
            try {
                List<HttpCookie> parsedCookies = HttpCookie.parse(header);
                cookies.addAll(parsedCookies);
            } catch (IllegalArgumentException e) {
                logger.warn("Error parsing cookie header: {} - {}", header, e.getMessage());
            }
        }
        // Delegate to the HttpCookie-based update.
        updateCookies(cookies);
    }

    /**
     * Validates that the given HttpCookie has both a non-null and non-empty name and value.
     */
    private boolean isValidCookie(HttpCookie cookie) {
        return cookie.getName() != null && !cookie.getName().isEmpty() && cookie.getValue() != null
                && !cookie.getValue().isEmpty();
    }

    /**
     * Handles HTTP response like Python's _request_json
     */
    private JsonElement handleResponse(ContentResponse response, String operation) throws HoneywellTCCException {
        String content = response.getContentAsString();
        int status = response.getStatus();

        // Match Python's debug logging exactly
        String requestPath = operation.replace(BASE_URL, "");
        logger.debug("Request to {} - Status: {}, Headers: {}", requestPath, status, response.getHeaders());

        if (status == HttpStatus.OK_200) {
            String contentType = response.getHeaders().get(HttpHeader.CONTENT_TYPE);
            if (contentType != null && contentType.contains("application/json")) {
                try {
                    JsonElement result = JsonParser.parseString(content);
                    updateCookiesFromHeaders(response.getHeaders().getValuesList(HttpHeader.SET_COOKIE));
                    return result;
                } catch (Exception e) {
                    logger.error("Failed to parse JSON response: {}", content);
                    throw new HoneywellTCCInvalidResponseException("Invalid JSON response");
                }
            } else {
                logger.error("Unexpected response type: {}", contentType);
                logger.error("Response text: {}", content);
                throw new HoneywellTCCInvalidResponseException(
                        String.format("Unexpected response type from %s: %s", requestPath, contentType));
            }
        } else if (status == 429) {
            logger.error("Rate limit exceeded: {}", status);
            logger.error("Response text: {}", content);
            throw new HoneywellTCCRateLimitException("You are being rate-limited. Try waiting a bit.");
        } else if (status == HttpStatus.UNAUTHORIZED_401) {
            throw new HoneywellTCCSessionExpiredException("Session has timed out.");
        } else {
            logger.error("API returned {} from {} request", status, requestPath);
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

    private ContentResponse executeRequest(Request request, String operation) throws HoneywellTCCException {
        try {
            ContentResponse response = request.send();
            return response;
        } catch (Exception e) {
            logger.error("Request failed: {}", e.getMessage());
            throw new HoneywellTCCException("Request failed: " + e.getMessage(), e);
        }
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
     * Executes an HTTP request to the given URL and returns the JSON response.
     *
     * This method extracts the response content as a string and converts it into
     * a JsonElement using Gson.
     */
    public JsonElement executeRequest(String url) throws HoneywellTCCException {
        try {
            ContentResponse response = httpClient.newRequest(url).timeout(10, TimeUnit.SECONDS).send();
            String jsonString = response.getContentAsString();
            JsonElement jsonElement = gson.fromJson(jsonString, JsonElement.class);
            return Objects.requireNonNull(jsonElement, "Parsed JSON element is null.");
        } catch (Exception e) { // Catch only the exceptions that may actually be thrown.
            throw new HoneywellTCCException("Failed to execute request: " + e.getMessage(), e);
        }
    }

    // Method to update cookies from response headers
    public void updateCookiesFromResponse(Response response) {
        // Extract the Set-Cookie header values from the response headers.
        Collection<String> cookieHeaders = response.getHeaders().getValuesList(HttpHeader.SET_COOKIE);
        if (cookieHeaders != null && !cookieHeaders.isEmpty()) {
            logger.debug("Received Set-Cookie headers: {}", cookieHeaders);
            // Delegate to the helper that converts header strings to HttpCookie objects.
            updateCookiesFromHeaders(cookieHeaders);
        }
    }

    // Method to get cookies as a header string for a given URL,
    // filtering cookies based on domain and path matching.
    private String getCookieHeader(String url) {
        try {
            // Retrieve cookies from the CookieStore for the given URL.
            List<HttpCookie> cookies = cookieManager.getCookieStore().get(java.net.URI.create(url));
            // Construct the cookie header as "name=value" pairs separated by "; "
            String cookieHeader = cookies.stream().map(cookie -> cookie.getName() + "=" + cookie.getValue())
                    .collect(Collectors.joining("; "));
            logger.debug("Constructed cookie header for {}: {}", url, cookieHeader);
            return cookieHeader;
        } catch (Exception e) {
            logger.debug("Failed to get cookie header for {}: {}", url, e.getMessage());
            return "";
        }
    }

    // Helper method to extract a proper value from the Fields entry; never returns null.
    private String extractValue(Fields formFields, String param) {
        org.eclipse.jetty.util.Fields.Field field = formFields.get(param);
        if (field != null) {
            String raw = field.getValue();
            if (raw != null && raw.startsWith(param + "=[") && raw.endsWith("]")) {
                // Remove the leading "param=[" and the trailing "]"
                return raw.substring(param.length() + 2, raw.length() - 1);
            }
            return (raw != null) ? raw : "";
        }
        return "";
    }

    // New helper method to log the complete POST request details
    public void logPostRequest(Request request, Fields formFields) {
        // Build the POST body using the extracted parameter values
        String pageValue = extractValue(formFields, PARAM_PAGE);
        String filterValue = extractValue(formFields, PARAM_FILTER);
        String requestBody = "page=" + pageValue + "&filter=" + filterValue;
        logger.debug("Post Request URL: {}", request.getURI());
        logger.debug("Post Request Method: {}", request.getMethod());
        logger.debug("Post Request Headers: {}", request.getHeaders());
        logger.debug("Post Request Body: {}", requestBody);
        // Additionally, log the raw Fields content for complete insight
        logger.debug("Raw Form Fields: {}", formFields.toString());
    }

    // Updated fetchLocations() method using BufferingResponseListener
    public CompletableFuture<@Nullable Void> fetchLocations() {
        // Define the endpoint for fetching locations
        String locationEndpoint = "https://www.mytotalconnectcomfort.com/portal/Location/GetLocationListData";

        // Create a request using GET for the locations endpoint with the required headers
        Request request = httpClient.newRequest(locationEndpoint).method(HttpMethod.GET)
                .header(HttpHeader.REFERER.asString(), BASE_URL).header(HttpHeader.ACCEPT.asString(), HEADER_ACCEPT);

        logger.debug("Fetching locations from: {}", locationEndpoint);

        // Create and return the CompletableFuture
        CompletableFuture<@Nullable Void> future = new CompletableFuture<>();

        // Use BufferingResponseListener to fully buffer the response
        BufferingResponseListener listener = new BufferingResponseListener() {
            @Override
            public void onComplete(org.eclipse.jetty.client.api.Result result) {
                if (result.isSucceeded()) {
                    Response response = result.getResponse();
                    int status = response.getStatus();
                    logger.debug("Location fetch response status: {}", status);
                    if (status == 200) {
                        String responseBody = new String(getContent(), StandardCharsets.UTF_8);
                        processLocationData(responseBody);
                        future.complete(null);
                    } else {
                        future.completeExceptionally(new HoneywellTCCException("Failed to fetch locations: " + status));
                    }
                } else {
                    future.completeExceptionally(result.getFailure());
                }
            }
        };

        // Send the request using the buffering listener
        request.send(listener);
        return future;
    }

    // Method to process location data
    private void processLocationData(String responseBody) {
        logger.info("Location data received: {}", responseBody);
        logger.debug("Location data (detailed): {}", responseBody);
    }
}
