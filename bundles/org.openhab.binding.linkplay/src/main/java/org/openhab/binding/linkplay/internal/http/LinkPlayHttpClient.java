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
    private @Nullable String ipAddress;

    @Activate
    public LinkPlayHttpClient(@Reference HttpClientFactory httpClientFactory) {
        this.httpClient = httpClientFactory.getCommonHttpClient();
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    private void validateIpAddress() throws IllegalStateException {
        if (ipAddress == null || ipAddress.isEmpty()) {
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
        String ipAddr = ipAddress;
        if (ipAddr == null || ipAddr.isEmpty()) {
            logger.warn("IP address is not configured.");
            return CompletableFuture.failedFuture(new IllegalStateException("IP address is not configured."));
        }

        String url = String.format("http://%s/httpapi.asp?%s", ipAddr, params);
        logger.debug("Sending request to LinkPlay device: {}", url);

        return CompletableFuture.supplyAsync(() -> {
            try {
                ContentResponse response = httpClient.GET(url);
                String content = response.getContentAsString();
                logger.debug("Received response: {}", content);
                return content;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException("Request interrupted", e);
            } catch (Exception e) {
                logger.debug("Request failed: {}", e.getMessage());
                throw new CompletionException(e);
            }
        });
    }
}
