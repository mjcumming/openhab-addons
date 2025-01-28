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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LinkPlayHttpClient} handles HTTP/HTTPS communication with LinkPlay devices.
 * It provides:
 * <ul>
 * <li>Automatic HTTPS/HTTP fallback with configurable ports</li>
 * <li>SSL certificate handling for secure communication</li>
 * <li>Asynchronous request handling with timeouts</li>
 * <li>Error handling and logging</li>
 * </ul>
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
@Component(service = LinkPlayHttpClient.class)
public class LinkPlayHttpClient {
    private final Logger logger = LoggerFactory.getLogger(LinkPlayHttpClient.class);

    /** HTTPS ports to try in order (443 = standard HTTPS, 4443 = alternative) */
    private static final int[] HTTPS_PORTS = { 443, 4443 };
    /** Standard HTTP port for fallback */
    private static final int HTTP_PORT = 80;
    /** Request timeout in milliseconds */
    private static final int TIMEOUT_MS = 2000;

    private final HttpClient httpClient;
    private final HttpClient sslHttpClient;

    @Activate
    public LinkPlayHttpClient(@Reference HttpClientFactory httpClientFactory) {
        this.httpClient = httpClientFactory.getCommonHttpClient();

        try {
            X509TrustManager trustManager = LinkPlaySslUtil.createLearningTrustManager(true, null);
            SSLContext sslContext = LinkPlaySslUtil.createSslContext(trustManager);
            this.sslHttpClient = LinkPlaySslUtil.createHttpsClient(sslContext);
            this.sslHttpClient.start();
            logger.debug("LinkPlay HTTPS client initialized");
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
            logger.warn("Error stopping LinkPlay HTTPS client: {}", e.getMessage());
        }
    }

    /**
     * Send a command request to a LinkPlay device with automatic HTTPS/HTTP fallback
     *
     * @param deviceIp IP address of the target device
     * @param command Raw command to send (will be URL encoded)
     * @return CompletableFuture with {@link CommandResult} containing either the response text or error message
     */
    public CompletableFuture<CommandResult> sendRequest(String deviceIp, String command) {
        if (deviceIp.isEmpty()) {
            return CompletableFuture.completedFuture(CommandResult.error("Device IP not configured"));
        }

        String encodedCommand = URLEncoder.encode(command, StandardCharsets.UTF_8);
        return CompletableFuture.supplyAsync(() -> {
            // Try HTTPS first
            for (int port : HTTPS_PORTS) {
                String url = String.format("https://%s:%d/httpapi.asp?command=%s", deviceIp, port, encodedCommand);
                try {
                    logger.trace("Attempting HTTPS request to {}: {}", deviceIp, url);
                    ContentResponse response = sslHttpClient.newRequest(url).timeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .send();

                    if (response.getStatus() == 200) {
                        String content = response.getContentAsString().trim();
                        return CommandResult.text(content);
                    }
                } catch (Exception e) {
                    logger.trace("HTTPS attempt failed on port {} for {}: {}", port, deviceIp, e.getMessage());
                    // Continue to next port
                }
            }

            // Fallback to HTTP
            String url = String.format("http://%s:%d/httpapi.asp?command=%s", deviceIp, HTTP_PORT, encodedCommand);
            try {
                logger.trace("Falling back to HTTP for {}: {}", deviceIp, url);
                ContentResponse response = httpClient.newRequest(url).timeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).send();

                if (response.getStatus() == 200) {
                    String content = response.getContentAsString().trim();
                    return CommandResult.text(content);
                }
                return CommandResult.error("HTTP error code: " + response.getStatus());
            } catch (Exception e) {
                String message = e.getMessage();
                return CommandResult.error(message != null ? message : e.getClass().getSimpleName());
            }
        });
    }

    /**
     * Perform a raw HTTP GET request to external services
     * Used primarily for metadata retrieval (e.g., album art, track info)
     *
     * @param url The complete URL to request
     * @return CompletableFuture with response text if successful, null otherwise
     */
    public CompletableFuture<@Nullable String> rawGetRequest(String url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ContentResponse response = httpClient.newRequest(url).timeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).send();
                if (response.getStatus() == 200) {
                    return response.getContentAsString();
                }
                return null;
            } catch (Exception e) {
                logger.debug("Raw GET request failed: {}", e.getMessage());
                return null;
            }
        });
    }
}
