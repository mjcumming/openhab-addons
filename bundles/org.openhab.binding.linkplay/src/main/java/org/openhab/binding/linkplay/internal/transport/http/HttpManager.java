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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.BindingConstants;
import org.openhab.binding.linkplay.internal.DeviceManager;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.core.common.ThreadPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link HttpManager} handles HTTP communication with LinkPlay devices.
 * 
 * This class is responsible for:
 * - Sending commands to control playback, volume, and device settings
 * - Managing periodic status polling for player and device state
 * - Coordinating multiroom functionality between master and slave devices
 * - Maintaining connection state and error reporting
 *
 * Communication is done via HTTP GET requests to the device's API endpoints.
 * Status polling intervals are configurable through the binding configuration.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class HttpManager {

    private static final Logger logger = LoggerFactory.getLogger(HttpManager.class);

    private final LinkPlayHttpClient httpClient;
    private final DeviceManager deviceManager;
    private final int playerStatusPollingInterval;
    private final int deviceStatusPollingInterval;
    private final LinkPlayConfiguration config;
    private final ScheduledExecutorService scheduler;

    private @Nullable ScheduledFuture<?> playerStatusPollingJob;
    private @Nullable ScheduledFuture<?> deviceStatusPollingJob;
    private static final int POLLING_INITIAL_DELAY_MS = 1000;

    public HttpManager(LinkPlayHttpClient client, DeviceManager deviceManager, ScheduledExecutorService scheduler) {
        this.httpClient = client;
        this.deviceManager = deviceManager;
        this.config = deviceManager.getConfig();
        this.scheduler = scheduler;

        // Get intervals from config
        this.playerStatusPollingInterval = config.getPlayerStatusPollingInterval();
        this.deviceStatusPollingInterval = config.getDeviceStatusPollingInterval();

        logger.trace("[{}] HttpManager created => playerPoll={}s, devicePoll={}s",
                deviceManager.getDeviceState().getDeviceName(), this.playerStatusPollingInterval,
                this.deviceStatusPollingInterval);
    }

    // ------------------------------------------------------------------------
    // Device Information Methods
    // ------------------------------------------------------------------------

    /**
     * Gets the player status from the device
     *
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> getPlayerStatus() {
        return httpClient.sendRequest(config.getIpAddress(), "getPlayerStatus");
    }

    /**
     * Gets the extended device status from the device
     *
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> getStatusEx() {
        return httpClient.sendRequest(config.getIpAddress(), "getStatusEx");
    }

    public CompletableFuture<CommandResult> reboot() {
        return httpClient.sendRequest(config.getIpAddress(), "reboot");
    }

    // ------------------------------------------------------------------------
    // Playback Control Methods
    // ------------------------------------------------------------------------

    /**
     * Play a specific URL directly
     *
     * @param url The URL to play
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> play(String url) {
        return httpClient.sendRequest(config.getIpAddress(), "setPlayerCmd:play:" + url);
    }

    /**
     * Play an M3U playlist URL
     *
     * @param url The M3U playlist URL
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> playM3u(String url) {
        return httpClient.sendRequest(config.getIpAddress(), "setPlayerCmd:m3u:play:" + url);
    }

    /**
     * Play a specific track by index in the current playlist
     *
     * @param index The 1-based index of the track to play
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> playIndex(int index) {
        if (index < 1) {
            logger.warn("Invalid playlist index: {}. Must be >= 1", index);
            return CompletableFuture.completedFuture(CommandResult.error("Invalid playlist index"));
        }
        return httpClient.sendRequest(config.getIpAddress(), "setPlayerCmd:playindex:" + index);
    }

    /**
     * Set the loop mode for the current playback
     *
     * @param mode Loop mode value (0-5)
     *            0: SHUFFLE: disabled, REPEAT: enabled - loop
     *            1: SHUFFLE: disabled, REPEAT: enabled - loop once
     *            2: SHUFFLE: enabled, REPEAT: enabled - loop
     *            3: SHUFFLE: enabled, REPEAT: disabled
     *            4: SHUFFLE: disabled, REPEAT: disabled
     *            5: SHUFFLE: enabled, REPEAT: enabled - loop once
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> setLoopMode(int mode) {
        if (mode < 0 || mode > 5) {
            logger.warn("Invalid loop mode: {}. Must be between 0-5", mode);
            return CompletableFuture.completedFuture(CommandResult.error("Invalid loop mode"));
        }
        return httpClient.sendRequest(config.getIpAddress(), "setPlayerCmd:loopmode:" + mode);
    }

    /**
     * Pause the current playback
     *
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> pause() {
        return httpClient.sendRequest(config.getIpAddress(), "setPlayerCmd:pause");
    }

    /**
     * Resume the current playback
     *
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> resume() {
        return httpClient.sendRequest(config.getIpAddress(), "setPlayerCmd:resume");
    }

    /**
     * Toggle between play and pause states
     *
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> togglePlayPause() {
        return httpClient.sendRequest(config.getIpAddress(), "setPlayerCmd:onepause");
    }

    /**
     * Stop the current playback
     *
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> stop() {
        return httpClient.sendRequest(config.getIpAddress(), "setPlayerCmd:stop");
    }

    /**
     * Skip to the previous track
     *
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> previous() {
        return httpClient.sendRequest(config.getIpAddress(), "setPlayerCmd:prev");
    }

    /**
     * Skip to the next track
     *
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> next() {
        return httpClient.sendRequest(config.getIpAddress(), "setPlayerCmd:next");
    }

    /**
     * Seek to a specific position in the current track
     *
     * @param seconds Position to seek to in seconds
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> seek(int seconds) {
        if (seconds < 0) {
            logger.warn("Invalid seek position: {}. Must be >= 0", seconds);
            return CompletableFuture.completedFuture(CommandResult.error("Invalid seek position"));
        }
        return httpClient.sendRequest(config.getIpAddress(), "setPlayerCmd:seek:" + seconds);
    }

    /**
     * Set the volume level
     *
     * @param volume Volume level (0-100)
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> setVolume(int volume) {
        if (volume < 0 || volume > 100) {
            logger.warn("Invalid volume: {}. Must be between {}-{}", volume, 0, 100);
            return CompletableFuture.completedFuture(CommandResult.error("Invalid volume"));
        }
        return httpClient.sendRequest(config.getIpAddress(), "setPlayerCmd:vol:" + volume);
    }

    /**
     * Increase the volume by one step
     *
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> volumeUp() {
        return httpClient.sendRequest(config.getIpAddress(), "setPlayerCmd:vol++");
    }

    /**
     * Decrease the volume by one step
     *
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> volumeDown() {
        return httpClient.sendRequest(config.getIpAddress(), "setPlayerCmd:vol--");
    }

    /**
     * Set the mute state
     *
     * @param mute Whether to mute the device
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> setMute(boolean mute) {
        return httpClient.sendRequest(config.getIpAddress(), "setPlayerCmd:mute:" + (mute ? "1" : "0"));
    }

    /**
     * Play a preset by number
     *
     * @param preset Preset number (0-10)
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> playPreset(int preset) {
        if (preset < 0 || preset > 10) {
            logger.warn("Invalid preset: {}. Must be between {}-{}", preset, 0, 10);
            return CompletableFuture.completedFuture(CommandResult.error("Invalid preset"));
        }
        return httpClient.sendRequest(config.getIpAddress(), "MCUKeyShortClick:" + preset);
    }

    /**
     * Get the total number of tracks in the current playlist
     *
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> getTrackCount() {
        return httpClient.sendRequest(config.getIpAddress(), "GetTrackNumber");
    }

    /**
     * Play a notification sound
     *
     * @param url URL of the notification sound to play
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> playNotification(String url) {
        return httpClient.sendRequest(config.getIpAddress(), "playPromptUrl:" + url);
    }

    // ------------------------------------------------------------------------
    // Input Source Control Methods
    // ------------------------------------------------------------------------

    /**
     * Set the input source for playback
     * 
     * @param source The source to switch to (e.g., "wifi", "line-in", "bluetooth")
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> setPlaySource(String source) {
        return httpClient.sendRequest(config.getIpAddress(), "setPlayerCmd:switchmode:" + source);
    }

    // ------------------------------------------------------------------------
    // Multiroom Control Methods
    // ------------------------------------------------------------------------

    /**
     * Get the list of slave devices in the current group
     *
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> getSlaveList() {
        return httpClient.sendRequest(config.getIpAddress(), "multiroom:getSlaveList");
    }

    /**
     * Set the volume for a specific slave device
     *
     * @param ipAddress IP address of the slave device
     * @param volume Volume level (0-100)
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> setSlaveVolume(String ipAddress, int volume) {
        if (volume < 0 || volume > 100) {
            logger.warn("Invalid volume: {}. Must be between {}-{}", volume, 0, 100);
            return CompletableFuture.completedFuture(CommandResult.error("Invalid volume"));
        }
        return httpClient.sendRequest(config.getIpAddress(), "multiroom:SlaveVolume:" + ipAddress + ":" + volume);
    }

    /**
     * Set the mute state for a specific slave device
     *
     * @param ipAddress IP address of the slave device
     * @param mute Whether to mute the slave device
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> setSlaveMute(String ipAddress, boolean mute) {
        return httpClient.sendRequest(config.getIpAddress(),
                "multiroom:SlaveMute:" + ipAddress + ":" + (mute ? "1" : "0"));
    }

    /**
     * Remove a slave device from the current group
     *
     * @param ipAddress IP address of the slave device to remove
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> kickoutSlave(String ipAddress) {
        return httpClient.sendRequest(config.getIpAddress(), "multiroom:SlaveKickout:" + ipAddress);
    }

    /**
     * Dissolve the current multiroom group
     *
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> ungroup() {
        return httpClient.sendRequest(config.getIpAddress(), "multiroom:Ungroup");
    }

    /**
     * Join a multiroom group as a slave
     *
     * @param masterIp IP address of the master device to join
     * @return A CompletableFuture containing the command result
     */
    public CompletableFuture<CommandResult> joinGroup(String masterIp) {
        return httpClient.sendRequest(config.getIpAddress(),
                "ConnectMasterAp:JoinGroupMaster:eth" + masterIp + ":wifi0.0.0.0");
    }

    public CompletableFuture<CommandResult> setPlaybackOptions(boolean repeat, boolean shuffle) {
        String command = String.format("setPlayerCmd:loopmode:%d:randommode:%d", repeat ? 1 : 0, shuffle ? 1 : 0);
        return httpClient.sendRequest(config.getIpAddress(), command);
    }

    // ------------------------------------------------------------------------
    // Raw HTTP Methods
    // ------------------------------------------------------------------------

    public CompletableFuture<@Nullable String> rawGetRequest(String url) {
        return httpClient.rawGetRequest(url);
    }

    // ------------------------------------------------------------------------
    // Polling Control Methods
    // ------------------------------------------------------------------------

    /**
     * Starts both player and device status polling.
     */
    public void startPolling() {
        try {
            // Get the thread pool and ensure it's started
            var threadPool = ThreadPoolManager.getScheduledPool(BindingConstants.BINDING_ID + "-http");
            if (threadPool == null) {
                logger.error("[{}] Failed to get thread pool for polling", config.getDeviceName());
                return;
            }

            // Start polling with proper error handling
            startPlayerStatusPolling();
            startDeviceStatusPolling();

            logger.debug("[{}] Successfully started polling with intervals: player={}s, device={}s",
                    config.getDeviceName(), playerStatusPollingInterval, deviceStatusPollingInterval);
        } catch (Exception e) {
            logger.error("[{}] Error starting polling: {}", config.getDeviceName(), e.getMessage(), e);
        }
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

        try {
            playerStatusPollingJob = scheduler.scheduleWithFixedDelay(this::pollPlayerStatus, POLLING_INITIAL_DELAY_MS,
                    playerStatusPollingInterval * 1000L, TimeUnit.MILLISECONDS);
            logger.debug("[{}] Started player status polling every {}s", config.getDeviceName(),
                    playerStatusPollingInterval);
        } catch (Exception e) {
            logger.error("[{}] Error starting player status polling: {}", config.getDeviceName(), e.getMessage(), e);
        }
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

        try {
            deviceStatusPollingJob = scheduler.scheduleWithFixedDelay(this::pollDeviceStatus, POLLING_INITIAL_DELAY_MS,
                    deviceStatusPollingInterval * 1000L, TimeUnit.MILLISECONDS);
            logger.debug("[{}] Started device status polling every {}s", config.getDeviceName(),
                    deviceStatusPollingInterval);
        } catch (Exception e) {
            logger.error("[{}] Error starting device status polling: {}", config.getDeviceName(), e.getMessage(), e);
        }
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
        try {
            logger.trace("[{}] Polling player status", config.getDeviceName());

            getPlayerStatus().thenAccept(result -> {
                try {
                    if (!result.isSuccess()) {
                        logger.warn("[{}] Player status poll failed: {}", config.getDeviceName(),
                                result.getErrorMessage());
                        deviceManager.handleCommunicationResult(false, "player status: " + result.getErrorMessage());
                        return;
                    }

                    result.getAsJson().ifPresentOrElse(json -> {
                        try {
                            deviceManager.handleGetPlayerStatusResponse(json);
                            deviceManager.handleCommunicationResult(true, "player status");
                        } catch (Exception e) {
                            logger.warn("[{}] Error processing player status response: {}", config.getDeviceName(),
                                    e.getMessage(), e);
                            deviceManager.handleCommunicationResult(false, "player status: processing error");
                        }
                    }, () -> {
                        logger.warn("[{}] Invalid JSON in player status response", config.getDeviceName());
                        deviceManager.handleCommunicationResult(false, "player status: invalid JSON");
                    });
                } catch (Exception e) {
                    logger.warn("[{}] Error handling player status response: {}", config.getDeviceName(),
                            e.getMessage(), e);
                    deviceManager.handleCommunicationResult(false, "player status: " + e.getMessage());
                }
            }).exceptionally(e -> {
                logger.warn("[{}] Player status poll failed: {}", config.getDeviceName(), e.getMessage());
                deviceManager.handleCommunicationResult(false, "player status: " + e.getMessage());
                return null;
            });
        } catch (Exception e) {
            logger.error("[{}] Critical error in player status polling: {}", config.getDeviceName(), e.getMessage(), e);
            deviceManager.handleCommunicationResult(false, "player status: critical error");
        }
    }

    /**
     * Polls device status (multiroom configuration, etc.)
     */
    private void pollDeviceStatus() {
        try {
            logger.trace("[{}] Polling device status", config.getDeviceName());

            httpClient.sendRequest(config.getIpAddress(), "getStatusEx").thenAccept(result -> {
                try {
                    if (!result.isSuccess()) {
                        logger.warn("[{}] Device status poll failed: {}", config.getDeviceName(),
                                result.getErrorMessage());
                        deviceManager.handleCommunicationResult(false, "device status: " + result.getErrorMessage());
                        return;
                    }

                    result.getAsJson().ifPresentOrElse(json -> {
                        try {
                            // Validate essential fields
                            if (!json.has("group") || !json.has("DeviceName")) {
                                logger.warn("[{}] Device status response missing required fields",
                                        config.getDeviceName());
                                deviceManager.handleCommunicationResult(false, "device status: missing fields");
                                return;
                            }

                            deviceManager.handleGetStatusExResponse(json);
                            deviceManager.handleCommunicationResult(true, "device status");
                        } catch (Exception e) {
                            logger.warn("[{}] Error processing device status response: {}", config.getDeviceName(),
                                    e.getMessage(), e);
                            deviceManager.handleCommunicationResult(false, "device status: processing error");
                        }
                    }, () -> {
                        logger.warn("[{}] Invalid JSON in device status response", config.getDeviceName());
                        deviceManager.handleCommunicationResult(false, "device status: invalid JSON");
                    });
                } catch (Exception e) {
                    logger.warn("[{}] Error handling device status response: {}", config.getDeviceName(),
                            e.getMessage(), e);
                    deviceManager.handleCommunicationResult(false, "device status: " + e.getMessage());
                }
            }).exceptionally(e -> {
                logger.warn("[{}] Device status poll failed: {}", config.getDeviceName(), e.getMessage());
                deviceManager.handleCommunicationResult(false, "device status: " + e.getMessage());
                return null;
            });
        } catch (Exception e) {
            logger.error("[{}] Critical error in device status polling: {}", config.getDeviceName(), e.getMessage(), e);
            deviceManager.handleCommunicationResult(false, "device status: critical error");
        }
    }

    /**
     * Clean up resources
     */
    public void dispose() {
        stopPolling();
    }
}
