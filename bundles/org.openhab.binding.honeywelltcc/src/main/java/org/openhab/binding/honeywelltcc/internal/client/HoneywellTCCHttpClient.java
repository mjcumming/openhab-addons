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

import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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
import org.eclipse.jetty.client.util.FormContentProvider;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpField;
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

    public static HoneywellTCCHttpClient create(HttpClient httpClient, String username, String password,
            ScheduledExecutorService scheduler) throws HoneywellTCCException {
        return new HoneywellTCCHttpClient(httpClient, username, password, scheduler);
    }

    private HoneywellTCCHttpClient(HttpClient httpClient, String username, String password,
            ScheduledExecutorService scheduler) {
        this.httpClient = httpClient;
        this.username = username;
        this.password = password;
        this.scheduler = scheduler;
        this.gson = new Gson();

        // Configure timeouts using constants
        httpClient.setConnectTimeout(HTTP_REQUEST_TIMEOUT_SEC * 1000L);
        httpClient.setIdleTimeout(HTTP_REQUEST_TIMEOUT_SEC * 1000L);

        // Configure client like Python requests
        this.httpClient.setFollowRedirects(true);
        this.httpClient.setUserAgentField(null);

        // Initialize headers exactly like Python
        initializeHeaders();
        startKeepalive();
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
        try {
            synchronized (sessionLock) {
                if (isAuthenticated) {
                    keepalive();
                }
            }
        } catch (HoneywellTCCException e) {
            logger.debug("Keepalive failed: {}", e.getMessage());
        }
    }

    /**
     * Keeps session alive (matches Python's keepalive())
     */
    public void keepalive() throws HoneywellTCCException {
        logger.debug("Performing keepalive request");
        try {
            Request request = httpClient.newRequest(BASE_URL).method(HttpMethod.GET).timeout(REQUEST_TIMEOUT_SEC,
                    TimeUnit.SECONDS);
            addHeaders(request);

            ContentResponse response = executeRequest(request, "keepalive");
            logger.debug("Keepalive response status: {}", response.getStatus());

            synchronized (sessionLock) {
                if (response.getStatus() != HttpStatus.OK_200) {
                    isAuthenticated = false;
                    logger.debug("Session timed out, status: {}", response.getStatus());
                    throw new HoneywellTCCSessionExpiredException("Session has timed out");
                }

                updateCookies(response);
                lastAuthTime = System.currentTimeMillis();
                logger.debug("Session refreshed successfully");
            }
        } catch (Exception e) {
            synchronized (sessionLock) {
                isAuthenticated = false;
            }
            logger.error("Keepalive request failed: {}", e.getMessage());
            throw new HoneywellTCCException("Keepalive failed: " + e.getMessage(), e);
        }
    }

    /**
     * Executes request with retry logic (matches Python's _retries_login)
     */
    private <T> T executeWithRetry(String operation, RequestExecutor<T> executor) throws HoneywellTCCException {
        synchronized (sessionLock) {
            if (!isAuthenticated || (System.currentTimeMillis() - lastAuthTime) > SESSION_TIMEOUT_MS) {
                logger.debug("Session needs refresh, attempting keepalive");
                try {
                    keepalive();
                } catch (HoneywellTCCSessionExpiredException e) {
                    logger.info("Session expired, performing full login");
                    login();
                }
            }
        }

        try {
            return executor.execute();
        } catch (HoneywellTCCSessionExpiredException e) {
            logger.info("Session expired during request, retrying after login");
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
    public void login() throws HoneywellTCCException {
        logger.debug("Starting login process for user: {}", username);

        try {
            // Step 1: GET request for initial cookies
            Request getRequest = httpClient.newRequest(BASE_URL).method(HttpMethod.GET).timeout(REQUEST_TIMEOUT_SEC,
                    TimeUnit.SECONDS);
            addHeaders(getRequest);

            ContentResponse getResponse = executeRequest(getRequest, "login initial GET");
            logger.debug("Initial GET response status: {}, headers: {}", getResponse.getStatus(),
                    getResponse.getHeaders());

            if (getResponse.getStatus() != HttpStatus.OK_200) {
                throw new HoneywellTCCAuthException("Failed to fetch login page: " + getResponse.getStatus());
            }

            updateCookies(getResponse);
            updateRefererHeader(BASE_URL);

            // Step 2: POST login data
            Fields formFields = new Fields();
            formFields.put(FORM_USERNAME, username);
            formFields.put(FORM_PASSWORD, password);
            formFields.put(FORM_REMEMBER_ME, LOGIN_REMEMBER_ME_VALUE);
            formFields.put(FORM_TIME_OFFSET, LOGIN_TIME_OFFSET_VALUE);

            Request postRequest = httpClient.newRequest(BASE_URL).method(HttpMethod.POST)
                    .timeout(REQUEST_TIMEOUT_SEC, TimeUnit.SECONDS).content(new FormContentProvider(formFields));
            addHeaders(postRequest);

            ContentResponse postResponse = executeRequest(postRequest, "login POST");
            String responseText = postResponse.getContentAsString();
            logger.debug("Login POST response status: {}, headers: {}", postResponse.getStatus(),
                    postResponse.getHeaders());

            // Check for specific error messages like Python
            if (responseText.contains("Invalid username or password")) {
                throw new HoneywellTCCAuthException("Invalid username or password");
            }

            if (postResponse.getStatus() != HttpStatus.OK_200) {
                throw new HoneywellTCCAuthException(
                        "Login failed with status " + postResponse.getStatus() + ": " + responseText);
            }

            updateCookies(postResponse);

            synchronized (sessionLock) {
                isAuthenticated = true;
                lastAuthTime = System.currentTimeMillis();
                logger.info("Login successful for user: {}", username);
            }

            // Perform initial keepalive like Python
            keepalive();

        } catch (Exception e) {
            synchronized (sessionLock) {
                isAuthenticated = false;
            }
            logger.error("Login failed: {}", e.getMessage());
            throw new HoneywellTCCAuthException("Login failed: " + e.getMessage(), e);
        }
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
            String url = String.format("%s/Location/GetLocationListData", BASE_URL);

            Fields formFields = new Fields();
            formFields.put("page", "1");
            formFields.put("filter", "");

            Request request = createRequest(url, HttpMethod.POST).content(new FormContentProvider(formFields));

            ContentResponse response = executeRequest(request, "locations");
            JsonElement jsonResponse = handleResponse(response, "locations");
            if (!jsonResponse.isJsonArray()) {
                throw new HoneywellTCCInvalidResponseException("Expected JSON array response");
            }
            return jsonResponse.getAsJsonArray();
        });
    }

    public long getLastAuthTime() {
        return lastAuthTime;
    }

    /**
     * Updates the session's cookie jar with cookies from API response.
     * Matches Python's _update_cookies() exactly.
     */
    private void updateCookies(ContentResponse response) {
        logger.debug("Updating cookies from response headers: {}", response.getHeaders());

        for (HttpField field : response.getHeaders()) {
            if (!field.getName().equalsIgnoreCase("Set-Cookie")) {
                continue;
            }

            String cookieStr = field.getValue();
            logger.debug("Processing cookie: {}", cookieStr);

            // Parse cookie string (name=value; path=/; domain=.example.com)
            String[] cookieParts = cookieStr.split(";");
            String[] nameValue = cookieParts[0].split("=", 2);

            if (nameValue.length == 2) {
                String name = nameValue[0].trim();
                String value = nameValue[1].trim();

                // Don't store empty or "deleted" cookies
                if (!value.isEmpty() && !"deleted".equalsIgnoreCase(value)) {
                    cookies.put(name, value);
                    logger.debug("Added cookie: {}={}", name, value);
                } else {
                    cookies.remove(name);
                    logger.debug("Removed cookie: {}", name);
                }
            }
        }

        // Update cookie header like Python
        if (!cookies.isEmpty()) {
            String cookieHeader = cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("; "));
            headers.put(HttpHeader.COOKIE.asString(), cookieHeader);
            logger.debug("Updated cookie header: {}", cookieHeader);
        } else {
            headers.remove(HttpHeader.COOKIE.asString());
            logger.debug("Removed cookie header - no cookies present");
        }
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
                    updateCookies(response);
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
     * Creates a new request with proper headers and cookies.
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
     * Adds current headers to request
     * Ensures all requests have up-to-date headers
     */
    private void addHeaders(Request request) {
        headers.forEach(request::header);
        logger.debug("Added headers to request: {}", headers);
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
}
