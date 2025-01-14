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
package org.openhab.binding.linkplay.internal.http;

import static org.openhab.binding.linkplay.internal.LinkPlayBindingConstants.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.library.types.NextPreviousType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.PlayPauseType;
import org.openhab.core.types.Command;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LinkPlayHttpClient} handles all HTTP interactions with the LinkPlay device.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
@Component(service = LinkPlayHttpClient.class)
public class LinkPlayHttpClient {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayHttpClient.class);

    private final HttpClient httpClient;
    private final HttpClient sslHttpClient;

    // Default timeouts
    private static final int TIMEOUT_MS = 2000;
    private static final int[] HTTPS_PORTS = { 443, 4443 };
    private static final int HTTP_PORT = 80;

    @Activate
    public LinkPlayHttpClient(final @Reference HttpClientFactory httpClientFactory) {
        logger.debug("Initializing LinkPlay HTTP client");
        this.httpClient = httpClientFactory.getCommonHttpClient();

        // Create a separate SSL context + client for HTTPS
        try {
            X509TrustManager trustManager = LinkPlaySslUtil.createLearningTrustManager(true, null);
            SSLContext sslContext = LinkPlaySslUtil.createSslContext(trustManager);
            this.sslHttpClient = LinkPlaySslUtil.createHttpsClient(sslContext);
            this.sslHttpClient.start();
            logger.debug("LinkPlay HTTPS client initialized");
        } catch (Exception e) {
            logger.error("Failed to create SSL HTTP client: {}", e.getMessage());
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
            logger.warn("Error stopping HTTPS client: {}", e.getMessage());
        }
    }

    /**
     * Returns a raw string response with extended status from the device.
     */
    public CompletableFuture<String> getStatusEx(String ipAddress) {
        return sendCommand(ipAddress, "getStatusEx");
    }

    /**
     * Returns a raw string response with player status from the device.
     */
    public CompletableFuture<String> getPlayerStatus(String ipAddress) {
        return sendCommand(ipAddress, "getPlayerStatus");
    }

    public CompletableFuture<String> joinGroup(String ipAddress, String masterIP) {
        return sendCommand(ipAddress, String.format("multiroom/join?master=%s", masterIP));
    }

    public CompletableFuture<String> leaveGroup(String ipAddress) {
        return sendCommand(ipAddress, "multiroom/leave");
    }

    public CompletableFuture<String> ungroup(String ipAddress) {
        return sendCommand(ipAddress, "multiroom/ungroup");
    }

    public CompletableFuture<String> kickoutSlave(String ipAddress, String slaveIP) {
        return sendCommand(ipAddress, String.format("multiroom/kickout?slave=%s", slaveIP));
    }

    public CompletableFuture<String> setGroupVolume(String ipAddress, String slaveIPs, int volume) {
        return sendCommand(ipAddress, String.format("multiroom/setGroupVolume?slaves=%s&volume=%d", slaveIPs, volume));
    }

    public CompletableFuture<String> setGroupMute(String ipAddress, String slaveIPs, boolean mute) {
        return sendCommand(ipAddress,
                String.format("multiroom/setGroupMute?slaves=%s&mute=%d", slaveIPs, mute ? 1 : 0));
    }

    /**
     * Sends a command, returning a raw response string (no JSON parsing).
     */
    public CompletableFuture<String> sendCommand(String ipAddress, String command) {
        if (ipAddress.isEmpty()) {
            throw new IllegalArgumentException("IP address must not be empty");
        }
        return sendRequest(ipAddress, "command=" + command);
    }

    /**
     * Example of sending a command with a value (like volume, mute, etc.).
     */
    public CompletableFuture<String> sendCommand(String ipAddress, String command, Command value) {
        if (ipAddress.isEmpty()) {
            throw new IllegalArgumentException("IP address must not be empty");
        }
        String commandValue = formatCommandValue(command, value);
        return sendRequest(ipAddress, "command=" + commandValue);
    }

    private String formatCommandValue(String command, Command value) {
        // same logic as before, just returning a string
        if (CHANNEL_VOLUME.equals(command) && value instanceof PercentType) {
            return "setPlayerCmd:vol:" + ((PercentType) value).intValue();
        }
        // More logic for Mute, Control, etc.
        // ...
        return command;
    }

    private CompletableFuture<String> sendRequest(String ipAddress, String params) {
        return CompletableFuture.supplyAsync(() -> {
            // Try HTTPS first
            for (int port : HTTPS_PORTS) {
                String url = String.format("https://%s:%d/httpapi.asp?%s", ipAddress, port, params);
                try {
                    logger.debug("Sending HTTPS request to {}", url);
                    ContentResponse response = sslHttpClient.newRequest(url)
                            .timeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).send();

                    if (response.getStatus() == 200) {
                        String content = response.getContentAsString();
                        if (content.contains("error") || content.contains("fail")) {
                            throw new LinkPlayApiException("API error: " + content);
                        }
                        return content;
                    }
                } catch (Exception e) {
                    logger.debug("HTTPS request failed on port {}: {}", port, e.getMessage());
                }
            }

            // Fall back to HTTP
            String url = String.format("http://%s:%d/httpapi.asp?%s", ipAddress, HTTP_PORT, params);
            try {
                logger.debug("Falling back to HTTP request: {}", url);
                ContentResponse response = httpClient.newRequest(url)
                        .timeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .send();

                if (response.getStatus() == 200) {
                    String content = response.getContentAsString();
                    if (content.contains("error") || content.contains("fail")) {
                        throw new LinkPlayApiException("API error: " + content);
                    }
                    return content;
                }
                throw new LinkPlayCommunicationException("HTTP error " + response.getStatus());
            } catch (Exception e) {
                throw new CompletionException(new LinkPlayCommunicationException("Request failed", e));
            }
        });
    }
}
