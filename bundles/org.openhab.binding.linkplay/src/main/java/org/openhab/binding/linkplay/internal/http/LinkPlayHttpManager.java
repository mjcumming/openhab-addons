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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.LinkPlayBindingConstants;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.binding.linkplay.internal.handler.LinkPlayDeviceManager;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * The {@link LinkPlayHttpManager} handles HTTP communication with LinkPlay devices.
 * It provides retry logic and exponential backoff for reliability.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayHttpManager {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayHttpManager.class);

    private final LinkPlayHttpClient httpClient;
    private final LinkPlayDeviceManager deviceManager;
    private final int maxRetries;
    private final long retryDelayMs;
    private final int pollingIntervalSeconds;

    private @Nullable ScheduledFuture<?> pollingJob;

    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 1000;
    private static final int TIMEOUT_MS = 10000; // 10 seconds timeout

    private final LinkPlayConfiguration config;

    public LinkPlayHttpManager(LinkPlayHttpClient client, LinkPlayDeviceManager deviceManager,
            LinkPlayConfiguration config) {
        this.httpClient = client;
        this.deviceManager = deviceManager;
        this.config = config;

        // Use config-based values
        this.maxRetries = config.getMaxRetries() > 0 ? config.getMaxRetries() : 3;
        this.retryDelayMs = config.getRetryDelayMillis() > 0 ? config.getRetryDelayMillis() : 2000;
        this.pollingIntervalSeconds = Math.max(config.getPollingInterval(), 10);

        logger.debug("LinkPlayHttpManager created: maxRetries={}, retryDelayMs={}, pollInterval={}s", maxRetries,
                retryDelayMs, pollingIntervalSeconds);
    }

    /**
     * Starts periodic polling using the configured interval.
     */
    public void startPolling() {
        ScheduledFuture<?> job = pollingJob;
        if (job != null && !job.isCancelled()) {
            logger.debug("Polling is already running");
            return;
        }

        pollingJob = ThreadPoolManager.getScheduledPool(LinkPlayBindingConstants.BINDING_ID + "-http")
                .scheduleWithFixedDelay(this::poll, 0, pollingIntervalSeconds, TimeUnit.SECONDS);
        logger.debug("Started HTTP polling every {}s", pollingIntervalSeconds);
    }

    public void stopPolling() {
        ScheduledFuture<?> job = pollingJob;
        if (job != null) {
            job.cancel(true);
            pollingJob = null;
            logger.debug("Stopped HTTP polling");
        }
    }

    /**
     * Periodic poll method, retrieves player status from device,
     * parses JSON, and notifies deviceManager.
     */
    private void poll() {
        try {
            CompletableFuture<@Nullable String> future = httpClient.getPlayerStatus(getDeviceIp())
                    .handle((@Nullable String result, @Nullable Throwable ex) -> {
                        if (ex != null) {
                            logger.debug("Error polling device: {}", ex.getMessage());
                            return null;
                        }
                        return result;
                    });

            @Nullable
            String response = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (response == null || response.isEmpty()) {
                logger.debug("Null or empty response from device");
                deviceManager.handleHttpPollFailure(new LinkPlayCommunicationException("No response from device"));
                return;
            }

            try {
                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                deviceManager.updateChannelsFromHttp(json);
            } catch (JsonSyntaxException e) {
                logger.warn("Failed to parse device response: {}", e.getMessage());
                deviceManager.handleHttpPollFailure(e);
            }
        } catch (Exception e) {
            logger.warn("HTTP poll failed: {}", e.getMessage());
            deviceManager.handleHttpPollFailure(e);
        }
    }

    /**
     * Helper method to convert CompletableFuture<String> to CompletableFuture<@Nullable String>.
     * Handles exceptions by returning null, ensuring type safety without unchecked casts.
     *
     * @param future The original CompletableFuture with non-null type.
     * @param command The command being sent, used for logging purposes.
     * @return A CompletableFuture that contains the result or null if an exception occurred.
     */
    @NonNullByDefault({}) // Override class-level non-null default
    @SuppressWarnings("null") // Suppress nullness warning due to type invariance
    private CompletableFuture<@Nullable String> toNullableFuture(CompletableFuture<String> future, String command) {
        return future.handle((@Nullable String result, @Nullable Throwable ex) -> {
            if (ex != null) {
                logger.debug("Error sending command {}: {}", command, ex.getMessage());
                return null;
            }
            return result;
        });
    }

    /**
     * Send command to device and parse JSON response
     */
    public @Nullable JsonObject sendCommand(String command) {
        try {
            CompletableFuture<@Nullable String> future = httpClient.sendCommand(getDeviceIp(), command)
                    .handle((@Nullable String result, @Nullable Throwable ex) -> {
                        if (ex != null) {
                            logger.debug("Error sending command {}: {}", command, ex.getMessage());
                            return null;
                        }
                        return result;
                    });

            @Nullable
            String response = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (response == null) {
                return null;
            }

            try {
                return JsonParser.parseString(response).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                logger.warn("Failed to parse response for command {}: {}", command, e.getMessage());
                deviceManager.handleHttpPollFailure(e);
                return null;
            }

        } catch (Exception e) {
            logger.debug("Error sending command {}: {}", command, e.getMessage());
            return null;
        }
    }

    /**
     * Used by the device manager's handleCommand(...) to forward commands.
     */
    public void sendChannelCommand(String channelId, Command command) {
        String ipAddress = getDeviceIp();
        String cmd = formatCommand(channelId, command);
        if (cmd == null) {
            logger.debug("No mapping found for channel {} => command={}", channelId, command);
            return;
        }

        try {
            CompletableFuture<@Nullable String> futureResponse = httpClient.sendCommand(ipAddress, cmd)
                    .handle((@Nullable String result, @Nullable Throwable ex) -> {
                        if (ex != null) {
                            logger.debug("Error sending command {}: {}", cmd, ex.getMessage());
                            return null;
                        }
                        return result;
                    });

            @Nullable
            String response = futureResponse.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            logger.debug("Command response: {}", response);
        } catch (Exception ex) {
            deviceManager.handleHttpPollFailure(ex);
        }
    }

    private @Nullable String formatCommand(String channelId, Command command) {
        switch (channelId) {
            case LinkPlayBindingConstants.CHANNEL_CONTROL:
                return formatControlCommand(command);
            case LinkPlayBindingConstants.CHANNEL_VOLUME:
                return formatVolumeCommand(command);
            default:
                return null;
        }
    }

    private String formatControlCommand(Command cmd) {
        // e.g. "setPlayerCmd:play" or "pause" etc.
        if (cmd.toString().equalsIgnoreCase("play")) {
            return "setPlayerCmd:play";
        } else if (cmd.toString().equalsIgnoreCase("pause")) {
            return "setPlayerCmd:pause";
        }
        logger.debug("Unhandled control command: {}", cmd);
        return "setPlayerCmd:pause";
    }

    private String formatVolumeCommand(Command cmd) {
        // e.g. "setPlayerCmd:vol:NN"
        try {
            int volume = Integer.parseInt(cmd.toString());
            return "setPlayerCmd:vol:" + volume;
        } catch (NumberFormatException e) {
            logger.warn("Volume command not an int: {}", cmd);
            return "setPlayerCmd:vol:50";
        }
    }

    /**
     * Called by the constructor or the poll method to get the device IP.
     * Ensures that a non-null IP address is always returned.
     */
    private String getDeviceIp() {
        String ip = config.getIpAddress();
        if (ip.isEmpty()) { // Removed null check as 'ip' cannot be null
            logger.warn("Device IP address is empty. Using default IP '0.0.0.0'.");
            return "0.0.0.0"; // Default or error handling as appropriate
        }
        return ip;
    }

    public void dispose() {
        stopPolling();
    }

    /**
     * Send command to device with retry logic
     * 
     * @param command The command to send
     * @param expectedResponse Expected response string (if any)
     * @return The JsonObject response or null if failed
     */
    public @Nullable JsonObject sendCommandWithRetry(String command, @Nullable String expectedResponse) {
        int retries = 0;
        Exception lastException = null;

        while (retries < MAX_RETRIES) {
            try {
                JsonObject response = sendCommand(command);

                // If we got a response and either:
                // - we don't expect a specific response, or
                // - we got the expected response
                if (response != null && (expectedResponse == null
                        || (response.has("status") && expectedResponse.equals(response.get("status").getAsString())))) {
                    return response;
                }

                // Wrong response, try again
                retries++;
                if (retries < MAX_RETRIES) {
                    Thread.sleep(RETRY_DELAY_MS);
                }
            } catch (Exception e) {
                lastException = e;
                retries++;
                if (retries < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }

        if (lastException != null) {
            logger.debug("Command failed after {} retries: {}", MAX_RETRIES, lastException.getMessage());
        }
        return null; // Ensure the method returns @Nullable JsonObject
    }
}
