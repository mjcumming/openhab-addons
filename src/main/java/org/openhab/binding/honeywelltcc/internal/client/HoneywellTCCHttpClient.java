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

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.FormContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.Fields;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.AuthError;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.HoneywellTCCError;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.RateLimitError;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.RequestError;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.SessionError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP client for Honeywell Total Comfort Control API
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class HoneywellTCCHttpClient {
    private static final String BASE_URL = "https://www.mytotalconnectcomfort.com/portal";
    private static final int REQUEST_TIMEOUT = 30;

    private final Logger logger = LoggerFactory.getLogger(HoneywellTCCHttpClient.class);
    private final HttpClient httpClient;
    private final String username;
    private final String password;

    public HoneywellTCCHttpClient(String username, String password) {
        this.username = username;
        this.password = password;
        this.httpClient = new HttpClient();
        configureHttpClient();
    }

    private void configureHttpClient() {
        httpClient.setFollowRedirects(false);
        httpClient.setConnectTimeout(TimeUnit.SECONDS.toMillis(REQUEST_TIMEOUT));
        try {
            httpClient.start();
        } catch (Exception e) {
            logger.error("Failed to start HTTP client: {}", e.getMessage());
        }
    }

    public void login() throws HoneywellTCCError {
        try {
            // First GET request to get session cookie
            ContentResponse response = httpClient.newRequest(BASE_URL)
                    .method(HttpMethod.GET)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .send();

            if (response.getStatus() != 200) {
                throw new RequestError("Failed to get session cookie: " + response.getStatus());
            }

            // Now POST login credentials
            Fields fields = new Fields();
            fields.put("UserName", username);
            fields.put("Password", password);
            fields.put("timeOffset", "480");

            response = httpClient.newRequest(BASE_URL + "/Account/LogOn")
                    .method(HttpMethod.POST)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .content(new FormContentProvider(fields))
                    .send();

            if (response.getStatus() != 200) {
                throw new AuthError("Login failed: " + response.getStatus());
            }

            String content = response.getContentAsString();
            if (content.contains("\"success\":false")) {
                throw new AuthError("Invalid credentials");
            }

        } catch (Exception e) {
            if (e instanceof HoneywellTCCError) {
                throw (HoneywellTCCError) e;
            }
            throw new RequestError("Login request failed: " + e.getMessage(), e);
        }
    }

    public String get(String path) throws HoneywellTCCError {
        try {
            ContentResponse response = createRequest(HttpMethod.GET, path).send();
            validateResponse(response);
            return response.getContentAsString();
        } catch (Exception e) {
            if (e instanceof HoneywellTCCError) {
                throw (HoneywellTCCError) e;
            }
            throw new RequestError("GET request failed: " + e.getMessage(), e);
        }
    }

    public String post(String path, Map<String, String> data) throws HoneywellTCCError {
        try {
            Fields fields = new Fields();
            data.forEach(fields::put);

            ContentResponse response = createRequest(HttpMethod.POST, path)
                    .content(new FormContentProvider(fields))
                    .send();

            validateResponse(response);
            return response.getContentAsString();
        } catch (Exception e) {
            if (e instanceof HoneywellTCCError) {
                throw (HoneywellTCCError) e;
            }
            throw new RequestError("POST request failed: " + e.getMessage(), e);
        }
    }

    private Request createRequest(HttpMethod method, String path) {
        return httpClient.newRequest(BASE_URL + path)
                .method(method)
                .header("User-Agent", "Mozilla/5.0")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json, text/javascript, */*; q=0.01");
    }

    private void validateResponse(ContentResponse response) throws HoneywellTCCError {
        int status = response.getStatus();
        String content = response.getContentAsString();

        if (status == 429) {
            throw new RateLimitError("Rate limit exceeded");
        }

        if (status == 401 || status == 403) {
            throw new SessionError("Session expired");
        }

        if (status != 200) {
            throw new RequestError("Request failed with status: " + status);
        }

        if (content.contains("\"success\":false")) {
            throw new RequestError("Request failed: " + content);
        }
    }

    public void close() {
        try {
            httpClient.stop();
        } catch (Exception e) {
            logger.error("Failed to stop HTTP client: {}", e.getMessage());
        }
    }
} 