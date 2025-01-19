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
import org.openhab.binding.linkplay.internal.utils.HexConverter;
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
                JsonObject rawJson = JsonParser.parseString(response).getAsJsonObject();
                JsonObject cleanJson = parsePlayerStatus(rawJson);
                deviceManager.handleGetPlayerStatusResponse(cleanJson);
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

    private JsonObject parsePlayerStatus(JsonObject rawJson) {
        JsonObject cleanJson = new JsonObject();

        // Fix playback status mapping
        if (rawJson.has("status")) {
            String status = getAsString(rawJson, "status").toLowerCase();
            switch (status) {
                case "play":
                    cleanJson.addProperty("playStatus", "PLAY");
                    break;
                case "pause":
                    cleanJson.addProperty("playStatus", "PAUSE");
                    break;
                case "stop":
                    cleanJson.addProperty("playStatus", "STOP");
                    break;
                default:
                    cleanJson.addProperty("playStatus", "STOP");
            }
        }

        // Parse and decode hex-encoded fields
        if (rawJson.has("Title")) {
            cleanJson.addProperty("title", HexConverter.hexToString(getAsString(rawJson, "Title")));
        }
        if (rawJson.has("Artist")) {
            cleanJson.addProperty("artist", HexConverter.hexToString(getAsString(rawJson, "Artist")));
        }
        if (rawJson.has("Album")) {
            cleanJson.addProperty("album", HexConverter.hexToString(getAsString(rawJson, "Album")));
        }

        // Parse volume and mute
        if (rawJson.has("vol")) {
            cleanJson.addProperty("volume", getAsInt(rawJson, "vol", 0));
        }
        if (rawJson.has("mute")) {
            cleanJson.addProperty("mute", getAsBoolean(rawJson, "mute"));
        }

        // Parse and convert time values to seconds
        if (rawJson.has("totlen")) {
            cleanJson.addProperty("durationSeconds", getAsInt(rawJson, "totlen", 0) / 1000.0);
        }
        if (rawJson.has("curpos")) {
            cleanJson.addProperty("positionSeconds", getAsInt(rawJson, "curpos", 0) / 1000.0);
        }

        // Parse loop mode into separate shuffle/repeat flags
        if (rawJson.has("loop")) {
            int loopMode = getAsInt(rawJson, "loop", 0);
            switch (loopMode) {
                case 0: // No repeat, no shuffle
                    cleanJson.addProperty("shuffle", false);
                    cleanJson.addProperty("repeat", false);
                    break;
                case 1: // Repeat one, no shuffle
                    cleanJson.addProperty("shuffle", false);
                    cleanJson.addProperty("repeat", true);
                    break;
                case 2: // Repeat all, no shuffle
                    cleanJson.addProperty("shuffle", false);
                    cleanJson.addProperty("repeat", true);
                    break;
                case 3: // Shuffle on, no repeat
                    cleanJson.addProperty("shuffle", true);
                    cleanJson.addProperty("repeat", false);
                    break;
                case 4: // No repeat, no shuffle
                    cleanJson.addProperty("shuffle", false);
                    cleanJson.addProperty("repeat", false);
                    break;
                case 5: // Shuffle and repeat
                    cleanJson.addProperty("shuffle", true);
                    cleanJson.addProperty("repeat", true);
                    break;
                default:
                    cleanJson.addProperty("shuffle", false);
                    cleanJson.addProperty("repeat", false);
            }
        }

        // Map source from mode and vendor
        String source = "UNKNOWN";
        if (rawJson.has("vendor")) {
            String vendor = getAsString(rawJson, "vendor");
            if (!vendor.isEmpty()) {
                source = vendor;
            }
        }
        if (source.equals("UNKNOWN") && rawJson.has("mode")) {
            int mode = getAsInt(rawJson, "mode", 0);
            source = LinkPlayBindingConstants.PLAYBACK_MODES.getOrDefault(mode, "UNKNOWN");
        }
        cleanJson.addProperty("source", source);

        if (rawJson.has("RSSI")) {
            cleanJson.addProperty("wifiSignal", getAsInt(rawJson, "RSSI", 0));
        }

        return cleanJson;
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
            logger.trace("[{}] pollDeviceStatus() -> JSON: {}", config.getDeviceName(), response);

            try {
                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                deviceManager.handleGetStatusExResponse(json);
                deviceManager.handleCommunicationResult(true);
            } catch (JsonSyntaxException e) {
                logger.warn("[{}] Failed to parse device status JSON: {}", config.getDeviceName(), e.getMessage());
                deviceManager.handleCommunicationResult(false);
            }
        } catch (Exception e) {
            logger.warn("[{}] Device status poll failed: {}", config.getDeviceName(), e.getMessage());
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

            // For group commands, immediately poll status
            if (command.startsWith("joinGroup:") || command.startsWith("leaveGroup") || command.startsWith("ungroup")
                    || command.startsWith("kickoutSlave:")) {
                pollDeviceStatus();
            }

            if (response == null) {
                return null;
            }

            try {
                return JsonParser.parseString(response).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                logger.warn("[{}] Failed to parse response for command='{}': {}", config.getDeviceName(), command,
                        e.getMessage());
                deviceManager.handleCommunicationResult(false);
                return null;
            }

        } catch (Exception e) {
            logger.warn("[{}] Exception sending command='{}': {}", config.getDeviceName(), command, e.getMessage());
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

    // Add utility methods for JSON parsing
    private String getAsString(JsonObject obj, String key) {
        try {
            if (!obj.has(key) || obj.get(key).isJsonNull()) {
                return "";
            }
            return obj.get(key).getAsString();
        } catch (Exception e) {
            logger.trace("[{}] Failed to get string value for '{}': {}", config.getDeviceName(), key, e.getMessage());
            return "";
        }
    }

    private int getAsInt(JsonObject obj, String key, int defaultValue) {
        try {
            if (!obj.has(key) || obj.get(key).isJsonNull()) {
                return defaultValue;
            }
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            logger.trace("[{}] Failed to get int value for '{}': {}", config.getDeviceName(), key, e.getMessage());
            return defaultValue;
        }
    }

    private boolean getAsBoolean(JsonObject obj, String key) {
        try {
            if (!obj.has(key) || obj.get(key).isJsonNull()) {
                return false;
            }
            // Attempt direct boolean
            try {
                return obj.get(key).getAsBoolean();
            } catch (Exception ignored) {
                // fallback
            }
            String val = obj.get(key).getAsString();
            return "true".equalsIgnoreCase(val) || "1".equals(val) || "on".equalsIgnoreCase(val);
        } catch (Exception e) {
            logger.trace("[{}] Failed to get boolean value for '{}': {}", config.getDeviceName(), key, e.getMessage());
            return false;
        }
    }
}
