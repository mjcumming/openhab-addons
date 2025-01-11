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

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
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
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LinkPlayHttpClient} handles all HTTP interactions with the LinkPlay device.
 *
 * @author Michael Cumming - Initial contribution
 */
@Component(service = LinkPlayHttpClient.class)
@NonNullByDefault
public class LinkPlayHttpClient {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayHttpClient.class);
    private final HttpClient httpClient;
    private final HttpClient sslHttpClient;
    private @Nullable String ipAddress;

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int[] HTTPS_PORTS = { 443, 4443 };
    private static final int HTTP_PORT = 80;

    @Activate
    public LinkPlayHttpClient(@Reference HttpClientFactory httpClientFactory) {
        this.httpClient = httpClientFactory.getCommonHttpClient();

        try {
            X509TrustManager trustManager = LinkPlaySslUtil.createLearningTrustManager(true, null);
            SSLContext sslContext = LinkPlaySslUtil.createSslContext(trustManager);
            this.sslHttpClient = LinkPlaySslUtil.createHttpsClient(sslContext);
        } catch (Exception e) {
            logger.warn("Failed to create SSL HTTP client: {}", e.getMessage());
            throw new IllegalStateException("Failed to create SSL HTTP client", e);
        }
    }

    public void setIpAddress(@Nullable String ipAddress) {
        this.ipAddress = ipAddress != null && !ipAddress.trim().isEmpty() ? ipAddress.trim() : null;
    }

    private void validateIpAddress() throws IllegalStateException {
        String currentIp = ipAddress;
        if (currentIp == null || currentIp.isEmpty()) {
            throw new IllegalStateException("IP address is not configured.");
        }
    }

    /**
     * Sends a command to the device
     *
     * @param command The command to send
     * @return CompletableFuture containing the response
     */
    public CompletableFuture<String> sendCommand(String command) {
        validateIpAddress();
        return sendRequest("command=" + command);
    }

    /**
     * Sends a command with a value to the device
     *
     * @param command The command to send
     * @param value The command value/parameter
     * @return CompletableFuture containing the response
     */
    public CompletableFuture<String> sendCommand(String command, Command value) {
        validateIpAddress();
        String commandValue = formatCommandValue(command, value);
        return sendRequest("command=" + commandValue);
    }

    private String formatCommandValue(String command, Command value) {
        // Format the command based on the channel and value type
        switch (command) {
            case CHANNEL_VOLUME:
                if (value instanceof PercentType) {
                    return "setPlayerCmd:vol:" + ((PercentType) value).intValue();
                }
                break;
            case CHANNEL_MUTE:
                if (value instanceof OnOffType) {
                    return "setPlayerCmd:mute:" + (OnOffType.ON.equals(value) ? "1" : "0");
                }
                break;
            case CHANNEL_CONTROL:
                if (value instanceof PlayPauseType) {
                    return "setPlayerCmd:" + (PlayPauseType.PLAY.equals(value) ? "play" : "pause");
                } else if (value instanceof NextPreviousType) {
                    return "setPlayerCmd:" + (NextPreviousType.NEXT.equals(value) ? "next" : "prev");
                }
                break;
        }
        logger.warn("Unsupported command combination: {} with value {}", command, value);
        return command;
    }

    private CompletableFuture<String> sendRequest(String params) {
        // validateIpAddress() must be called before this method
        return CompletableFuture.supplyAsync(() -> {
            // Try HTTPS first on different ports
            for (int port : HTTPS_PORTS) {
                String httpsUrl = String.format("https://%s:%d/httpapi.asp?%s", ipAddress, port, params);
                try {
                    logger.debug("Trying HTTPS request to {}", httpsUrl);
                    ContentResponse response = sslHttpClient.newRequest(httpsUrl)
                            .timeout(CONNECT_TIMEOUT_MS + READ_TIMEOUT_MS, TimeUnit.MILLISECONDS).send();

                    if (response.getStatus() == 200) {
                        String content = response.getContentAsString();
                        logger.debug("Received HTTPS response: {}", content);
                        return content;
                    }
                } catch (Exception e) {
                    logger.debug("HTTPS request failed on port {}: {}", port, e.getMessage());
                }
            }

            // Fall back to HTTP if HTTPS fails
            String httpUrl = String.format("http://%s:%d/httpapi.asp?%s", ipAddress, HTTP_PORT, params);
            logger.debug("Falling back to HTTP request: {}", httpUrl);

            try {
                ContentResponse response = httpClient.newRequest(httpUrl)
                        .timeout(CONNECT_TIMEOUT_MS + READ_TIMEOUT_MS, TimeUnit.MILLISECONDS).send();

                int status = response.getStatus();
                if (status == 200) {
                    String content = response.getContentAsString();
                    logger.debug("Received HTTP response: {}", content);
                    return content;
                } else {
                    throw new IOException("HTTP error " + status);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException("Request interrupted", e);
            } catch (TimeoutException e) {
                throw new CompletionException("Request timed out", e);
            } catch (Exception e) {
                logger.debug("HTTP request failed: {}", e.getMessage());
                throw new CompletionException(e);
            }
        });
    }
}
