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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.LinkPlayBindingConstants;
import org.openhab.binding.linkplay.internal.LinkPlayDeviceManager;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
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
    private final int playerStatusPollingInterval;
    private final int deviceStatusPollingInterval;
    private final LinkPlayConfiguration config;

    private @Nullable ScheduledFuture<?> playerStatusPollingJob;
    private @Nullable ScheduledFuture<?> deviceStatusPollingJob;
    private static final int TIMEOUT_MS = 10000; // 10-second timeout

    public LinkPlayHttpManager(LinkPlayHttpClient client, LinkPlayDeviceManager deviceManager) {
        this.httpClient = client;
        this.deviceManager = deviceManager;
        this.config = deviceManager.getConfig();

        // Get intervals from config
        this.playerStatusPollingInterval = config.getPlayerStatusPollingInterval();
        this.deviceStatusPollingInterval = config.getDeviceStatusPollingInterval();

        logger.trace("[{}] LinkPlayHttpManager created => playerPoll={}s, devicePoll={}s",
                deviceManager.getDeviceState().getDeviceName(), this.playerStatusPollingInterval,
                this.deviceStatusPollingInterval);
    }

    // ------------------------------------------------------------------------
    // Polling Control Methods
    // ------------------------------------------------------------------------

    /**
     * Starts both player and device status polling.
     */
    public void startPolling() {
        startPlayerStatusPolling();
        startDeviceStatusPolling();
    }

    /**
     * Starts player status polling using the configured interval.
     */
    public void startPlayerStatusPolling() {
        if (playerStatusPollingInterval <= 0) {
            logger.debug("[{}] Player status polling is disabled (interval=0).", config.getDeviceName());
            return;
        }

        final @Nullable ScheduledFuture<?> currentJob = playerStatusPollingJob;
        if (currentJob != null && !currentJob.isCancelled()) {
            logger.debug("[{}] Player status polling already running.", config.getDeviceName());
            return;
        }

        playerStatusPollingJob = ThreadPoolManager.getScheduledPool(LinkPlayBindingConstants.BINDING_ID + "-http")
                .scheduleWithFixedDelay(this::pollPlayerStatus, 2, playerStatusPollingInterval, TimeUnit.SECONDS);
        logger.debug("[{}] Started player status polling every {}s", config.getDeviceName(),
                playerStatusPollingInterval);
    }

    /**
     * Starts device status polling using the configured interval.
     */
    public void startDeviceStatusPolling() {
        if (deviceStatusPollingInterval <= 0) {
            logger.debug("[{}] Device status polling is disabled (interval=0).", config.getDeviceName());
            return;
        }

        final @Nullable ScheduledFuture<?> currentJob = deviceStatusPollingJob;
        if (currentJob != null && !currentJob.isCancelled()) {
            logger.debug("[{}] Device status polling already running.", config.getDeviceName());
            return;
        }

        deviceStatusPollingJob = ThreadPoolManager.getScheduledPool(LinkPlayBindingConstants.BINDING_ID + "-http")
                .scheduleWithFixedDelay(this::pollDeviceStatus, 1, deviceStatusPollingInterval, TimeUnit.SECONDS);
        logger.debug("[{}] Started device status polling every {}s", config.getDeviceName(),
                deviceStatusPollingInterval);
    }

    /**
     * Stops all polling activities.
     */
    public void stopPolling() {
        final @Nullable ScheduledFuture<?> playerJob = playerStatusPollingJob;
        if (playerJob != null) {
            playerJob.cancel(true);
            playerStatusPollingJob = null;
            logger.debug("[{}] Stopped player status polling", config.getDeviceName());
        }

        final @Nullable ScheduledFuture<?> deviceJob = deviceStatusPollingJob;
        if (deviceJob != null) {
            deviceJob.cancel(true);
            deviceStatusPollingJob = null;
            logger.debug("[{}] Stopped device status polling", config.getDeviceName());
        }
    }

    // ------------------------------------------------------------------------
    // Polling Implementation Methods
    // ------------------------------------------------------------------------

    /**
     * Polls player status (playback, volume, etc.)
     */
    private void pollPlayerStatus() {
        String ip = getDeviceIp();
        logger.trace("[{}] Polling player status at IP={}", config.getDeviceName(), ip);

        try {
            CompletableFuture<String> future = httpClient.getPlayerStatus(ip);
            String response = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            logger.trace("[{}] pollPlayerStatus() -> JSON: {}", config.getDeviceName(), response);

            try {
                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                deviceManager.handleGetPlayerStatusResponse(json);
                deviceManager.handleCommunicationResult(true);
            } catch (JsonSyntaxException e) {
                logger.warn("[{}] Failed to parse player status JSON: {}", config.getDeviceName(), e.getMessage());
                deviceManager.handleCommunicationResult(false);
            }
        } catch (Exception e) {
            logger.warn("[{}] Player status poll failed: {}", config.getDeviceName(), e.getMessage());
            deviceManager.handleCommunicationResult(false);
        }
    }

    /**
     * Polls device status (multiroom configuration, etc.)
     */
    private void pollDeviceStatus() {
        String ip = getDeviceIp();
        logger.trace("[{}] Polling device status at IP={}", config.getDeviceName(), ip);

        try {
            CompletableFuture<String> future = httpClient.getStatusEx(ip);
            String response = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            // Handle empty responses
            if (response == null || response.trim().isEmpty()) {
                logger.warn("[{}] Empty device status response from IP={}", config.getDeviceName(), ip);
                deviceManager.handleCommunicationResult(false);
                return;
            }

            logger.trace("[{}] pollDeviceStatus() -> JSON: {}", config.getDeviceName(), response);

            try {
                JsonObject json = JsonParser.parseString(response).getAsJsonObject();

                // Validate essential fields
                if (!json.has("group") || !json.has("DeviceName")) {
                    logger.warn("[{}] Device status response missing required fields from IP={}: {}",
                            config.getDeviceName(), ip, response);
                    deviceManager.handleCommunicationResult(false);
                    return;
                }

                // Process the device status
                deviceManager.handleGetStatusExResponse(json);
                deviceManager.handleCommunicationResult(true);

            } catch (JsonSyntaxException e) {
                logger.warn("[{}] Invalid JSON response from IP={}: {} - Response: {}", config.getDeviceName(), ip,
                        e.getMessage(), response);
                deviceManager.handleCommunicationResult(false);
            }
        } catch (Exception e) {
            // Log the full exception for debugging
            logger.warn("[{}] Device status poll failed for IP={}: {} - {}", config.getDeviceName(), ip,
                    e.getClass().getSimpleName(), e.getMessage());
            deviceManager.handleCommunicationResult(false);
        }
    }

    public void sendChannelCommand(String channelId, Command command) {
        String ip = getDeviceIp();
        @Nullable
        String cmd = formatCommand(channelId, command);
        if (cmd == null) {
            logger.trace("[{}] No mapping for channel='{}' => command='{}'", config.getDeviceName(), channelId,
                    command);
            return;
        }

        logger.trace("[{}] Sending command='{}' to IP={}", config.getDeviceName(), cmd, ip);
        try {
            CompletableFuture<@Nullable String> futureResponse = httpClient.sendCommand(ip, cmd)
                    .handle((@Nullable String result, @Nullable Throwable ex) -> {
                        if (ex != null) {
                            logger.warn("[{}] Error sending command='{}' => {}", config.getDeviceName(), cmd,
                                    ex.getMessage());
                            return null;
                        }
                        return result;
                    });

            @Nullable
            String response = futureResponse.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            logger.trace("[{}] Command response => {}", config.getDeviceName(), response);
        } catch (Exception ex) {
            logger.warn("[{}] Exception sending command='{}' => {}", config.getDeviceName(), cmd, ex.getMessage());
            deviceManager.handleCommunicationResult(false);
        }
    }

    public @Nullable JsonObject sendCommand(String command) {
        String ip = getDeviceIp();
        try {
            CompletableFuture<@Nullable String> future = httpClient.sendCommand(ip, command)
                    .handle((@Nullable String result, @Nullable Throwable ex) -> {
                        if (ex != null) {
                            logger.warn("[{}] Error sending command='{}': {}", config.getDeviceName(), command,
                                    ex.getMessage());
                            return null;
                        }
                        return result;
                    });

            @Nullable
            String response = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            // Handle empty or invalid responses
            if (response == null || response.trim().isEmpty()) {
                logger.debug("[{}] Empty response for command='{}', treating as success", config.getDeviceName(),
                        command);
                // For multiroom commands, empty response is normal and indicates success
                if (command.startsWith("multiroom:") || command.startsWith("joinGroup:")
                        || command.startsWith("leaveGroup") || command.startsWith("ungroup")
                        || command.startsWith("kickoutSlave:")) {
                    deviceManager.handleCommunicationResult(true);
                    pollDeviceStatus(); // Update status after multiroom command
                    return new JsonObject(); // Return empty object to indicate success
                }
                return null;
            }

            try {
                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                deviceManager.handleCommunicationResult(true);

                // For multiroom commands, always poll status after
                if (command.startsWith("multiroom:") || command.startsWith("joinGroup:")
                        || command.startsWith("leaveGroup") || command.startsWith("ungroup")
                        || command.startsWith("kickoutSlave:")) {
                    pollDeviceStatus();
                }

                return json;
            } catch (JsonSyntaxException e) {
                // For multiroom commands, some responses may not be JSON
                if (command.startsWith("multiroom:")) {
                    logger.debug("[{}] Non-JSON response for multiroom command='{}': {}", config.getDeviceName(),
                            command, response);
                    deviceManager.handleCommunicationResult(true);
                    pollDeviceStatus(); // Update status after multiroom command
                    return new JsonObject(); // Return empty object to indicate success
                } else {
                    logger.warn("[{}] Invalid JSON response for command='{}': {}", config.getDeviceName(), command,
                            e.getMessage());
                    deviceManager.handleCommunicationResult(false);
                    return null;
                }
            }
        } catch (Exception e) {
            logger.warn("[{}] Failed to send command='{}': {}", config.getDeviceName(), command, e.getMessage());
            deviceManager.handleCommunicationResult(false);
            return null;
        }
    }

    /**
     * Send a command to a specific IP address (for master control from a slave)
     */
    public @Nullable JsonObject sendCommand(String command, String targetIp) {
        try {
            CompletableFuture<@Nullable String> future = httpClient.sendCommand(targetIp, command)
                    .handle((@Nullable String result, @Nullable Throwable ex) -> {
                        if (ex != null) {
                            logger.warn("[{}] Error sending command='{}' to {}: {}", config.getDeviceName(), command,
                                    targetIp, ex.getMessage());
                            return null;
                        }
                        return result;
                    });

            @Nullable
            String response = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (response == null) {
                return null;
            }

            // Special case: some commands like ungroup return "OK"
            if ("OK".equals(response)) {
                JsonObject okResponse = new JsonObject();
                okResponse.addProperty("status", "OK");
                deviceManager.handleCommunicationResult(true);
                return okResponse;
            }

            try {
                return JsonParser.parseString(response).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                logger.warn("[{}] Failed to parse response for command='{}' to {}: {}", config.getDeviceName(), command,
                        targetIp, e.getMessage());
                deviceManager.handleCommunicationResult(false);
                return null;
            }

        } catch (Exception e) {
            logger.warn("[{}] Exception sending command='{}' to {}: {}", config.getDeviceName(), command, targetIp,
                    e.getMessage());
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
        logger.trace("[{}] Unhandled control command => {}", config.getDeviceName(), cmd);
        return "";
    }

    private String formatVolumeCommand(Command cmd) {
        try {
            int volume = Integer.parseInt(cmd.toString());
            return "setPlayerCmd:vol:" + volume;
        } catch (NumberFormatException e) {
            logger.warn("[{}] Volume command not an integer => {}", config.getDeviceName(), cmd);
            return "";
        }
    }

    private String formatMuteCommand(Command cmd) {
        boolean isOn = cmd.toString().equalsIgnoreCase("ON");
        return "setPlayerCmd:mute:" + (isOn ? "on" : "off");
    }

    private String formatRepeatCommand(Command cmd) {
        boolean isOn = cmd.toString().equalsIgnoreCase("ON");
        // Mode 0: Shuffle disabled, Repeat enabled - loop
        // Mode 4: Shuffle disabled, Repeat disabled
        return "setPlayerCmd:loopmode:" + (isOn ? "0" : "4");
    }

    private String formatShuffleCommand(Command cmd) {
        boolean isOn = cmd.toString().equalsIgnoreCase("ON");
        // Mode 2: Shuffle enabled, Repeat enabled - loop
        // Mode 4: Shuffle disabled, Repeat disabled
        return "setPlayerCmd:loopmode:" + (isOn ? "2" : "4");
    }

    private String getDeviceIp() {
        String ip = config.getIpAddress();
        if (ip.isEmpty()) {
            logger.warn("[{}] Device IP address is empty. Using default IP '0.0.0.0'.", config.getDeviceName());
            return "0.0.0.0";
        }
        return ip;
    }

    /**
     * Clean up resources
     */
    public void dispose() {
        stopPolling();
    }
}
