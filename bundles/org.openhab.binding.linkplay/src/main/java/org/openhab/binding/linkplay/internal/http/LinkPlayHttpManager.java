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
    private final int playerStatusPollingInterval;
    private final int deviceStatusPollingInterval;
    private final String deviceName;

    private @Nullable ScheduledFuture<?> playerStatusPollingJob;
    private @Nullable ScheduledFuture<?> deviceStatusPollingJob;
    private static final int TIMEOUT_MS = 10000; // 10-second timeout

    // Keep original constants for max retries, delay, etc.
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 1000;

    private final LinkPlayConfiguration config;

    private @Nullable String lastArtist;
    private @Nullable String lastTitle;

    public LinkPlayHttpManager(LinkPlayHttpClient client, LinkPlayDeviceManager deviceManager,
            LinkPlayConfiguration config, String deviceName) {
        this.httpClient = client;
        this.deviceManager = deviceManager;
        this.config = config;
        this.deviceName = deviceName;

        // Get intervals from config
        this.playerStatusPollingInterval = config.getPlayerStatusPollingInterval();
        this.deviceStatusPollingInterval = config.getDeviceStatusPollingInterval();

        logger.trace("[{}] LinkPlayHttpManager created => playerPoll={}s, devicePoll={}s", deviceName,
                this.playerStatusPollingInterval, this.deviceStatusPollingInterval);

        // Start polling automatically
        startPlayerStatusPolling();
        startDeviceStatusPolling();
    }

    // ------------------------------------------------------------------------
    // Polling Control Methods
    // ------------------------------------------------------------------------

    /**
     * Starts player status polling using the configured interval.
     */
    public void startPlayerStatusPolling() {
        if (playerStatusPollingInterval <= 0) {
            logger.debug("[{}] Player status polling is disabled (interval=0).", deviceName);
            return;
        }

        final @Nullable ScheduledFuture<?> currentJob = playerStatusPollingJob;
        if (currentJob != null && !currentJob.isCancelled()) {
            logger.debug("[{}] Player status polling already running.", deviceName);
            return;
        }

        playerStatusPollingJob = ThreadPoolManager.getScheduledPool(LinkPlayBindingConstants.BINDING_ID + "-http")
                .scheduleWithFixedDelay(this::pollPlayerStatus, 0, playerStatusPollingInterval, TimeUnit.SECONDS);
        logger.debug("[{}] Started player status polling every {}s", deviceName, playerStatusPollingInterval);
    }

    /**
     * Starts device status polling using the configured interval.
     */
    public void startDeviceStatusPolling() {
        if (deviceStatusPollingInterval <= 0) {
            logger.debug("[{}] Device status polling is disabled (interval=0).", deviceName);
            return;
        }

        final @Nullable ScheduledFuture<?> currentJob = deviceStatusPollingJob;
        if (currentJob != null && !currentJob.isCancelled()) {
            logger.debug("[{}] Device status polling already running.", deviceName);
            return;
        }

        deviceStatusPollingJob = ThreadPoolManager.getScheduledPool(LinkPlayBindingConstants.BINDING_ID + "-http")
                .scheduleWithFixedDelay(this::pollDeviceStatus, 0, deviceStatusPollingInterval, TimeUnit.SECONDS);
        logger.debug("[{}] Started device status polling every {}s", deviceName, deviceStatusPollingInterval);
    }

    /**
     * Stops all polling activities.
     */
    public void stopPolling() {
        final @Nullable ScheduledFuture<?> playerJob = playerStatusPollingJob;
        if (playerJob != null) {
            playerJob.cancel(true);
            playerStatusPollingJob = null;
            logger.debug("[{}] Stopped player status polling", deviceName);
        }

        final @Nullable ScheduledFuture<?> deviceJob = deviceStatusPollingJob;
        if (deviceJob != null) {
            deviceJob.cancel(true);
            deviceStatusPollingJob = null;
            logger.debug("[{}] Stopped device status polling", deviceName);
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
        logger.trace("[{}] Polling player status at IP={}", deviceName, ip);

        try {
            CompletableFuture<String> future = httpClient.getPlayerStatus(ip);
            String response = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            logger.trace("[{}] pollPlayerStatus() -> JSON: {}", deviceName, response);

            try {
                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                deviceManager.updateChannelsFromHttp(json);
                deviceManager.handleCommunicationResult(true);
            } catch (JsonSyntaxException e) {
                logger.warn("[{}] Failed to parse player status JSON: {}", deviceName, e.getMessage());
                deviceManager.handleCommunicationResult(false);
            }
        } catch (Exception e) {
            logger.warn("[{}] Player status poll failed: {}", deviceName, e.getMessage());
            deviceManager.handleCommunicationResult(false);
        }
    }

    /**
     * Polls device status (multiroom configuration, etc.)
     */
    private void pollDeviceStatus() {
        String ip = getDeviceIp();
        logger.trace("[{}] Polling device status at IP={}", deviceName, ip);

        try {
            CompletableFuture<String> future = httpClient.getStatusEx(ip);
            String response = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            logger.trace("[{}] pollDeviceStatus() -> JSON: {}", deviceName, response);

            try {
                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                deviceManager.updateMultiroomChannelsFromHttp(json);
                deviceManager.handleCommunicationResult(true);
            } catch (JsonSyntaxException e) {
                logger.warn("[{}] Failed to parse device status JSON: {}", deviceName, e.getMessage());
                deviceManager.handleCommunicationResult(false);
            }
        } catch (Exception e) {
            logger.warn("[{}] Device status poll failed: {}", deviceName, e.getMessage());
            deviceManager.handleCommunicationResult(false);
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
            deviceManager.handleCommunicationResult(false);
        }
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
                deviceManager.handleCommunicationResult(false);
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

    /**
     * Clean up resources
     */
    public void dispose() {
        stopPolling();
    }
}
