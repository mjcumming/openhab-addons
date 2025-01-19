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

package org.openhab.binding.linkplay.internal.transport.http;

import static org.openhab.binding.linkplay.internal.LinkPlayBindingConstants.CHANNEL_VOLUME;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.types.Command;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LinkPlayHttpClient} is responsible for low-level HTTP interactions with a LinkPlay device.
 * <p>
 * Key features:
 * - Tries multiple HTTPS ports (443, 4443), then falls back to HTTP on port 80.
 * - Uses a custom SSL context (mutual TLS) if needed.
 * - Provides async methods returning {@link CompletableFuture} for concurrency.
 * - Minimal error-checking in responses (e.g. "error" or "fail").
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
@Component(service = LinkPlayHttpClient.class)
public class LinkPlayHttpClient {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayHttpClient.class);

    // Default fallback ports for https
    private static final int[] HTTPS_PORTS = { 443, 4443 };
    private static final int HTTP_PORT = 80;

    private static final int TIMEOUT_MS = 2000;

    private final HttpClient httpClient; // for plain HTTP
    private final HttpClient sslHttpClient; // for HTTPS

    /**
     * Constructor sets up two Jetty HttpClients:
     * - A standard one from openHAB's HttpClientFactory (no TLS).
     * - A custom TLS-enabled client from {@link LinkPlaySslUtil}.
     */
    @Activate
    public LinkPlayHttpClient(@Reference HttpClientFactory httpClientFactory) {
        logger.debug("Initializing LinkPlay HTTP client (plain + SSL)...");

        // Plain HTTP client from openHAB's shared "commonHttpClient"
        this.httpClient = httpClientFactory.getCommonHttpClient();

        // Build an SSL context trusting all or a specific embedded cert
        try {
            X509TrustManager trustManager = LinkPlaySslUtil.createLearningTrustManager(true, null);
            SSLContext sslContext = LinkPlaySslUtil.createSslContext(trustManager);

            // Create a specialized Jetty client for HTTPS
            this.sslHttpClient = LinkPlaySslUtil.createHttpsClient(sslContext);
            this.sslHttpClient.start();

            logger.debug("LinkPlay HTTPS client successfully initialized");
        } catch (Exception e) {
            logger.error("Failed to create SSL HTTP client: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to create SSL HTTP client", e);
        }
    }

    @Deactivate
    protected void deactivate() {
        try {
            if (sslHttpClient.isStarted()) {
                sslHttpClient.stop();
                logger.debug("LinkPlay HTTPS client stopped");
            }
        } catch (Exception e) {
            logger.warn("Error stopping LinkPlay HTTPS client => {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------------
    // Common endpoints
    // ------------------------------------------------------------------------

    public CompletableFuture<String> getStatusEx(String ipAddress) {
        return sendCommand(ipAddress, "getStatusEx");
    }

    public CompletableFuture<String> getPlayerStatus(String ipAddress) {
        return sendCommand(ipAddress, "getPlayerStatus");
    }

    // Multiroom commands
    public CompletableFuture<String> joinGroup(String ip, String masterIP) {
        return sendCommand(ip, String.format("multiroom/join?master=%s", masterIP));
    }

    public CompletableFuture<String> leaveGroup(String ip) {
        return sendCommand(ip, "multiroom/leave");
    }

    public CompletableFuture<String> ungroup(String ip) {
        return sendCommand(ip, "multiroom/ungroup");
    }

    public CompletableFuture<String> kickoutSlave(String ip, String slaveIP) {
        return sendCommand(ip, String.format("multiroom/kickout?slave=%s", slaveIP));
    }

    /**
     * Send a command "command=..." to the device, returning a future with raw response text.
     */
    public CompletableFuture<String> sendCommand(String ipAddress, String command) {
        if (ipAddress.isEmpty()) {
            throw new IllegalArgumentException("IP address must not be empty");
        }
        return sendRequest(ipAddress, "command=" + command);
    }

    /**
     * For commands that carry a dynamic value (e.g. volume), we can pass openHAB commands
     * (PercentType, OnOffType, etc.) and convert them to the LinkPlay param string.
     */
    public CompletableFuture<String> sendCommand(String ipAddress, String command, Command value) {
        if (ipAddress.isEmpty()) {
            throw new IllegalArgumentException("IP address must not be empty");
        }
        String commandValue = formatCommandValue(command, value);
        return sendRequest(ipAddress, "command=" + commandValue);
    }

    private String formatCommandValue(String command, Command value) {
        if (CHANNEL_VOLUME.equals(command) && value instanceof PercentType) {
            return "setPlayerCmd:vol:" + ((PercentType) value).intValue();
        }
        // Extend for "mute", "control", etc.
        return command;
    }

    // ------------------------------------------------------------------------
    // Internal fallback logic: try HTTPS ports, then HTTP
    // ------------------------------------------------------------------------
    private CompletableFuture<String> sendRequest(String ipAddress, String params) {
        return CompletableFuture.supplyAsync(() -> {
            // Attempt HTTPS first
            for (int port : HTTPS_PORTS) {
                String url = String.format("https://%s:%d/httpapi.asp?%s", ipAddress, port, params);
                try {
                    logger.trace("Attempting HTTPS request => {}", url);
                    ContentResponse response = sslHttpClient.newRequest(url).timeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .send();

                    if (response.getStatus() == 200) {
                        String content = response.getContentAsString();
                        if (content.contains("error") || content.contains("fail")) {
                            // Use trace for getStatusEx during discovery, warn for other API calls
                            if (params.contains("getStatusEx")) {
                                logger.trace("API error response: {}", content);
                            } else {
                                logger.warn("API error response: {}", content);
                            }
                            throw new LinkPlayApiException("API error: " + content);
                        }
                        return content;
                    }
                    logger.trace("HTTPS request failed with status code {} for {}", response.getStatus(), url);
                } catch (Exception e) {
                    logger.trace("HTTPS attempt failed on port {}: {}", port, e.getMessage());
                    // Continue to next port
                }
            }

            // Fallback to HTTP
            String url = String.format("http://%s:%d/httpapi.asp?%s", ipAddress, HTTP_PORT, params);
            try {
                logger.trace("Falling back to HTTP => {}", url);
                ContentResponse response = httpClient.newRequest(url).timeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).send();

                if (response.getStatus() == 200) {
                    String content = response.getContentAsString();
                    if (content.contains("error") || content.contains("fail")) {
                        // Use trace for getStatusEx during discovery, warn for other API calls
                        if (params.contains("getStatusEx")) {
                            logger.trace("API error response: {}", content);
                        } else {
                            logger.warn("API error response: {}", content);
                        }
                        throw new LinkPlayApiException("API error: " + content);
                    }
                    return content;
                }
                String error = String.format("HTTP error code: %d", response.getStatus());
                // Use trace for getStatusEx during discovery, warn for other API calls
                if (params.contains("getStatusEx")) {
                    logger.trace(error);
                } else {
                    logger.warn(error);
                }
                throw new LinkPlayCommunicationException(error);
            } catch (Exception e) {
                String error = String.format("Request failed for %s: %s", url, e.getMessage());
                // Use trace for getStatusEx during discovery, warn for other API calls
                if (params.contains("getStatusEx")) {
                    logger.trace(error);
                } else {
                    logger.warn(error);
                }
                throw new CompletionException(new LinkPlayCommunicationException(error, e));
            }
        });
    }

    /**
     * Performs a simple GET request without LinkPlay-specific fallback logic.
     */
    public CompletableFuture<@Nullable String> rawGetRequest(String url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.trace("rawGetRequest -> {}", url);
                // Use the Jetty HttpClient directly
                ContentResponse response = httpClient.GET(url);
                if (response.getStatus() == 200) {
                    return response.getContentAsString();
                } else {
                    logger.warn("rawGetRequest failed with status={}, url={}", response.getStatus(), url);
                    return null;
                }
            } catch (Exception e) {
                logger.warn("rawGetRequest exception => {}", e.getMessage());
                return null;
            }
        });
    }
}
