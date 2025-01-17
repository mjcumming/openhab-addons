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
 * The {@link LinkPlayHttpManager} handles HTTP communication with LinkPlay devices,
 * including polling, command sending, and optional retry logic. {@link LinkPlayHttpClient}.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayHttpManager {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayHttpManager.class);

    private final LinkPlayHttpClient httpClient;
    private final LinkPlayDeviceManager deviceManager;
    private final int pollingIntervalSeconds;
    private final String deviceName;

    private @Nullable ScheduledFuture<?> pollingJob;
    private static final int TIMEOUT_MS = 10000; // 10-second timeout

    // Keep original constants for max retries, delay, etc.
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 1000;

    private final LinkPlayConfiguration config;

    public LinkPlayHttpManager(LinkPlayHttpClient client, LinkPlayDeviceManager deviceManager,
            LinkPlayConfiguration config, String deviceName) {
        this.httpClient = client;
        this.deviceManager = deviceManager;
        this.config = config;
        this.deviceName = deviceName;

        // Use config value directly - 0 means no polling, any positive number is the interval
        this.pollingIntervalSeconds = config.getPollingInterval();

        logger.trace("[{}] LinkPlayHttpManager created => pollInterval={}s", deviceName, this.pollingIntervalSeconds);
    }

    // ------------------------------------------------------------------------
    // 1) Periodic Polling
    // --
    /**
     * Starts periodic polling using the configured interval.
     */
    public void startPolling() {
        // If user sets poll=0, do not schedule
        if (pollingIntervalSeconds <= 0) {
            logger.debug("[{}] Polling is disabled (interval=0).", deviceName);
            return;
        }

        // Add null-safe check for existing pollingJob
        final @Nullable ScheduledFuture<?> currentPollingJob = pollingJob;
        if (currentPollingJob != null && !currentPollingJob.isCancelled()) {
            logger.debug("[{}] Polling is already running; skipping start.", deviceName);
            return;
        }

        pollingJob = ThreadPoolManager.getScheduledPool(LinkPlayBindingConstants.BINDING_ID + "-http")
                .scheduleWithFixedDelay(this::poll, 0, pollingIntervalSeconds, TimeUnit.SECONDS);
        logger.debug("[{}] Started HTTP polling every {}s", deviceName, pollingIntervalSeconds);
    }

    /**
     * Stops periodic polling.
     */
    public void stopPolling() {
        final @Nullable ScheduledFuture<?> currentPollingJob = pollingJob;
        if (currentPollingJob != null) {
            currentPollingJob.cancel(true);
            pollingJob = null;
            logger.debug("[{}] Stopped HTTP polling", deviceName);
        }
    }

    /**
     * Periodic poll method: retrieves player status, parses JSON, and notifies deviceManager.
     */
    private void poll() {
        String ip = getDeviceIp();
        logger.trace("[{}] Polling device at IP={}", deviceName, ip);

        try {
            CompletableFuture<@Nullable String> future = httpClient.getPlayerStatus(ip)
                    .handle((@Nullable String result, @Nullable Throwable ex) -> {
                        if (ex != null) {
                            logger.trace("[{}] Error polling device at {} => {}", deviceName, ip, ex.getMessage());
                            return null;
                        }
                        return result;
                    });

            @Nullable
            String response = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (response == null || response.isEmpty()) {
                logger.warn("[{}] Null or empty response from device IP={}", deviceName, ip);
                deviceManager.handleHttpPollFailure(new LinkPlayCommunicationException("No response from device"));
                return;
            }

            try {
                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                logger.trace("[{}] poll() -> JSON: {}", deviceName, json);
                deviceManager.updateChannelsFromHttp(json);
            } catch (JsonSyntaxException e) {
                logger.warn("[{}] Failed to parse device response => {}", deviceName, e.getMessage());
                deviceManager.handleHttpPollFailure(e);
            }
        } catch (Exception e) {
            logger.warn("[{}] HTTP poll failed => {}", deviceName,
                    e.getMessage() != null ? e.getMessage() : e.toString());
            deviceManager.handleHttpPollFailure(e);
        }
    }

    /**
     * Called by DeviceManager to do an immediate "getPlayerStatus" and update channels.
     * E.g., for REFRESH commands on volume/mute, etc.
     */
    public void refreshPlayerStatus() {
        logger.trace("[{}] refreshPlayerStatus() => doing one-off getPlayerStatus", deviceName);
        String ip = getDeviceIp();

        try {
            CompletableFuture<String> future = httpClient.getPlayerStatus(ip)
                    .handle((@Nullable String result, @Nullable Throwable ex) -> {
                        if (ex != null || result == null) {
                            logger.trace("[{}] Error or null result from getPlayerStatus: {}", deviceName,
                                    ex != null ? ex.getMessage() : "null result");
                            return "";
                        }
                        return result;
                    });
            String response = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (response.isEmpty()) {
                logger.warn("[{}] No or empty response => cannot update channels.", deviceName);
                return;
            }
            try {
                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                deviceManager.updateChannelsFromHttp(json);
            } catch (JsonSyntaxException e) {
                logger.warn("[{}] refreshPlayerStatus() => parse error: {}", deviceName, e.getMessage());
            }
        } catch (Exception e) {
            logger.warn("[{}] refreshPlayerStatus() => exception: {}", deviceName, e.getMessage());
        }
    }

    /**
     * Called by DeviceManager to do an immediate "getStatusEx" and update channels
     * that relate to multiroom (role, masterIP, slaveIPs, etc.).
     */
    public void refreshMultiroomStatus() {
        logger.trace("[{}] refreshMultiroomStatus() => doing one-off getStatusEx", deviceName);
        String ip = getDeviceIp();

        try {
            CompletableFuture<String> future = httpClient.getStatusEx(ip);
            String response = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (response.isEmpty()) {
                logger.warn("[{}] Empty response => cannot update multiroom channels.", deviceName);
                return;
            }

            try {
                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                deviceManager.updateChannelsFromHttp(json);
            } catch (JsonSyntaxException e) {
                logger.warn("[{}] refreshMultiroomStatus() => parse error: {}", deviceName, e.getMessage());
            }
        } catch (Exception e) {
            logger.warn("[{}] refreshMultiroomStatus() => exception: {}", deviceName, e.getMessage());
        }
    }

    public void sendChannelCommand(String channelId, Command command) {
        String ip = getDeviceIp();
        @Nullable
        String cmd = formatCommand(channelId, command);
        if (cmd == null) {
            logger.trace("[{}] No mapping for channel='{}' => command='{}'", deviceName, channelId, command);
            return;
        }

        logger.trace("[{}] Sending command='{}' to IP={}", deviceName, cmd, ip);
        try {
            CompletableFuture<@Nullable String> futureResponse = httpClient.sendCommand(ip, cmd)
                    .handle((@Nullable String result, @Nullable Throwable ex) -> {
                        if (ex != null) {
                            logger.warn("[{}] Error sending command='{}' => {}", deviceName, cmd, ex.getMessage());
                            return null;
                        }
                        return result;
                    });

            @Nullable
            String response = futureResponse.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            logger.trace("[{}] Command response => {}", deviceName, response);
        } catch (Exception ex) {
            logger.warn("[{}] Exception sending command='{}' => {}", deviceName, cmd, ex.getMessage());
            deviceManager.handleHttpPollFailure(ex);
        }
    }

    public @Nullable JsonObject sendCommandWithRetry(String command, @Nullable String expectedResponse) {
        int retries = 0;
        Exception lastException = null;

        while (retries < MAX_RETRIES) {
            try {
                JsonObject response = sendCommand(command);
                if (response != null) {
                    // If we do not expect a specific response or if it matches
                    if (expectedResponse == null || (response.has("status")
                            && expectedResponse.equals(response.get("status").getAsString()))) {
                        return response;
                    }
                }
                // Wrong or null response => try again
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
            logger.warn("[{}] Command '{}' failed after {} retries => {}", deviceName, command, MAX_RETRIES,
                    lastException.getMessage());
        }
        return null; // No success
    }

    public @Nullable JsonObject sendCommand(String command) {
        String ip = getDeviceIp();
        try {
            CompletableFuture<@Nullable String> future = httpClient.sendCommand(ip, command)
                    .handle((@Nullable String result, @Nullable Throwable ex) -> {
                        if (ex != null) {
                            logger.warn("[{}] Error sending command='{}': {}", deviceName, command, ex.getMessage());
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
                logger.warn("[{}] Failed to parse response for command='{}': {}", deviceName, command, e.getMessage());
                deviceManager.handleHttpPollFailure(e);
                return null;
            }

        } catch (Exception e) {
            logger.warn("[{}] Exception sending command='{}': {}", deviceName, command, e.getMessage());
            return null;
        }
    }

    private @Nullable String formatCommand(String channelId, Command command) {
        switch (channelId) {
            case LinkPlayBindingConstants.CHANNEL_CONTROL:
                return formatControlCommand(command);

            case LinkPlayBindingConstants.CHANNEL_VOLUME:
                return formatVolumeCommand(command);

            case LinkPlayBindingConstants.CHANNEL_MUTE:
                return formatMuteCommand(command);

            case LinkPlayBindingConstants.CHANNEL_REPEAT:
                return formatRepeatCommand(command);

            case LinkPlayBindingConstants.CHANNEL_SHUFFLE:
                return formatShuffleCommand(command);

            default:
                return null;
        }
    }

    private String formatControlCommand(Command cmd) {
        String c = cmd.toString().toUpperCase();
        if ("PLAY".equals(c)) {
            return "setPlayerCmd:play";
        } else if ("PAUSE".equals(c)) {
            return "setPlayerCmd:pause";
        }
        logger.trace("[{}] Unhandled control command => {}", deviceName, cmd);
        return "";
    }

    private String formatVolumeCommand(Command cmd) {
        try {
            int volume = Integer.parseInt(cmd.toString());
            return "setPlayerCmd:vol:" + volume;
        } catch (NumberFormatException e) {
            logger.warn("[{}] Volume command not an integer => {}", deviceName, cmd);
            return "";
        }
    }

    private String formatMuteCommand(Command cmd) {
        boolean isOn = cmd.toString().equalsIgnoreCase("ON");
        return "setPlayerCmd:mute:" + (isOn ? "on" : "off");
    }

    private String formatRepeatCommand(Command cmd) {
        boolean isOn = cmd.toString().equalsIgnoreCase("ON");
        return "setPlayerCmd:loop:" + (isOn ? "2" : "0");
    }

    private String formatShuffleCommand(Command cmd) {
        boolean isOn = cmd.toString().equalsIgnoreCase("ON");
        return "setPlayerCmd:random:" + (isOn ? "1" : "0");
    }

    private String getDeviceIp() {
        String ip = config.getIpAddress();
        if (ip.isEmpty()) {
            logger.warn("[{}] Device IP address is empty. Using default IP '0.0.0.0'.", deviceName);
            return "0.0.0.0";
        }
        return ip;
    }

    public void dispose() {
        stopPolling();
    }
}
