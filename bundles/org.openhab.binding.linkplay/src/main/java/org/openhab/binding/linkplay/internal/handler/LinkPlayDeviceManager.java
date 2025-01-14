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
 *
 * @author Michael Cumming - Initial contribution
 */
package org.openhab.binding.linkplay.internal.handler;

import static org.openhab.binding.linkplay.internal.LinkPlayBindingConstants.PROPERTY_UDN;

import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.LinkPlayBindingConstants;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpClient;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpManager;
import org.openhab.binding.linkplay.internal.upnp.LinkPlayUpnpManager;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.PlayPauseType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * The {@link LinkPlayDeviceManager} manages a LinkPlay device's state and communication.
 * It handles both HTTP and UPnP communication channels and maintains device status.
 * This class follows the OpenHAB binding development guidelines and patterns.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayDeviceManager {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayDeviceManager.class);
    private static final int DEFAULT_POLLING_INTERVAL_SECONDS = 10;

    // Channel IDs from LinkPlayBindingConstants
    protected static final String CHANNEL_CONTROL = LinkPlayBindingConstants.CHANNEL_CONTROL;
    protected static final String CHANNEL_TITLE = LinkPlayBindingConstants.CHANNEL_TITLE;
    protected static final String CHANNEL_ARTIST = LinkPlayBindingConstants.CHANNEL_ARTIST;
    protected static final String CHANNEL_ALBUM = LinkPlayBindingConstants.CHANNEL_ALBUM;
    protected static final String CHANNEL_ALBUM_ART = LinkPlayBindingConstants.CHANNEL_ALBUM_ART;
    protected static final String CHANNEL_VOLUME = LinkPlayBindingConstants.CHANNEL_VOLUME;
    protected static final String CHANNEL_MUTE = LinkPlayBindingConstants.CHANNEL_MUTE;

    private final String ipAddress;
    private final String deviceId;
    private final ScheduledExecutorService scheduler;
    private final LinkPlayThingHandler thingHandler;
    protected final LinkPlayHttpManager httpManager;
    protected final LinkPlayUpnpManager upnpManager;
    protected final LinkPlayGroupManager groupManager;

    private @Nullable ScheduledFuture<?> pollingJob;
    private boolean upnpSubscriptionActive = false;
    private static final int MAX_OFFLINE_COUNT = 3;
    private int failedPollCount = 0;

    /**
     * Constructs a new {@link LinkPlayDeviceManager}.
     */
    public LinkPlayDeviceManager(LinkPlayThingHandler thingHandler, ScheduledExecutorService scheduler,
            UpnpIOService upnpIOService, LinkPlayHttpClient httpClient) {
        this.thingHandler = thingHandler;
        Thing thing = thingHandler.getThing();
        this.ipAddress = thing.getConfiguration().get("ipAddress").toString();
        this.deviceId = thing.getUID().getId();
        this.scheduler = scheduler;

        // Create managers
        this.httpManager = new LinkPlayHttpManager(httpClient, ipAddress);
        this.upnpManager = new LinkPlayUpnpManager(upnpIOService, this, deviceId);
        this.groupManager = new LinkPlayGroupManager(httpManager);

        logger.debug("[{}] Initialized LinkPlayDeviceManager", deviceId);
    }

    /**
     * Initializes the device manager and starts communication.
     */
    public void initialize() {
        logger.debug("[{}] Initializing device manager", deviceId);

        try {
            // Initialize UPnP first
            String udn = thingHandler.getThing().getConfiguration().get(PROPERTY_UDN).toString();
            if (udn != null && !udn.isEmpty()) {
                upnpManager.register(udn);
                logger.debug("[{}] Registered UPnP manager with UDN: {}", deviceId, udn);
            } else {
                logger.warn("[{}] No UDN configured, UPnP features will be disabled", deviceId);
            }

            // Start HTTP polling
            startPolling();
            handleStatusUpdate(ThingStatus.ONLINE);
            logger.debug("[{}] Device manager initialized successfully", deviceId);
        } catch (RuntimeException e) {
            logger.warn("[{}] Error during device manager initialization: {}", deviceId, e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] Initialization error details:", deviceId, e);
            }
        }
    }

    /**
     * Gets a string value from a JSON object.
     * 
     * @param json The JSON object
     * @param key The key to look up
     * @return The string value or empty string if not found
     */
    private String getAsString(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element != null ? element.getAsString() : "";
    }

    /**
     * Gets an integer value from a JSON object.
     * 
     * @param json The JSON object
     * @param key The key to look up
     * @param defaultValue The default value if not found or invalid
     * @return The integer value or defaultValue if not found or invalid
     */
    private int getAsInt(JsonObject json, String key, int defaultValue) {
        JsonElement element = json.get(key);
        if (element != null && element.isJsonPrimitive()) {
            try {
                return element.getAsInt();
            } catch (NumberFormatException e) {
                logger.debug("[{}] Error parsing integer for key {}: {}", deviceId, key, e.getMessage());
            }
        }
        return defaultValue;
    }

    private boolean getAsBoolean(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element != null && element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            } else if (primitive.isString()) {
                return "1".equals(primitive.getAsString()) || "true".equalsIgnoreCase(primitive.getAsString());
            } else if (primitive.isNumber()) {
                return primitive.getAsInt() == 1;
            }
        }
        return false;
    }

    /**
     * Updates a channel state through the thing handler
     */
    protected void updateState(String channelId, State state) {
        thingHandler.handleStateUpdate(channelId, state);
    }

    /**
     * Updates the thing status
     */
    public void handleStatusUpdate(ThingStatus status) {
        thingHandler.handleStatusUpdate(status);
    }

    /**
     * Updates the thing status with detail and message
     */
    public void handleStatusUpdate(ThingStatus status, ThingStatusDetail detail, String message) {
        thingHandler.handleStatusUpdate(status, detail, message);
    }

    /**
     * Updates device online/offline status based on HTTP communication.
     */
    private void updateDeviceStatusBasedOnHttp(boolean isReachable) {
        if (isReachable) {
            failedPollCount = 0;
            if (getThing().getStatus() != ThingStatus.ONLINE) {
                handleStatusUpdate(ThingStatus.ONLINE);
            }
        } else {
            failedPollCount++;
            if (failedPollCount >= MAX_OFFLINE_COUNT) {
                handleStatusUpdate(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Device not reachable after " + MAX_OFFLINE_COUNT + " attempts");
            }
        }
    }

    /**
     * Starts periodic polling to retrieve device status.
     */
    private void startPolling() {
        stopPolling();

        pollingJob = scheduler.scheduleWithFixedDelay(() -> {
            try {
                httpManager.sendCommandWithRetry(ipAddress, "getPlayerStatus").thenAccept(response -> {
                    updateDeviceStatusBasedOnHttp(true);
                    logger.debug("[{}] Polling response: {}", deviceId, response);
                    try {
                        JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
                        updateChannelsFromHttp(jsonResponse);
                    } catch (Exception e) {
                        logger.warn("[{}] Failed to parse player status response: {}", deviceId, e.getMessage());
                    }
                }).exceptionally(error -> {
                    logger.warn("[{}] Failed to poll player status: {}", deviceId, error.getMessage());
                    updateDeviceStatusBasedOnHttp(false);
                    return null;
                });
            } catch (RuntimeException e) {
                logger.warn("[{}] Error during polling execution: {}", deviceId, e.getMessage());
                updateDeviceStatusBasedOnHttp(false);
            }
        }, 0, DEFAULT_POLLING_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stops the polling job if it exists.
     */
    private void stopPolling() {
        ScheduledFuture<?> job = pollingJob;
        if (job != null && !job.isCancelled()) {
            job.cancel(true);
            pollingJob = null;
        }
    }

    /**
     * Updates playback and device channels from UPnP data.
     * Note: UPnP updates are used for faster response but don't affect online status.
     *
     * @param status The UPnP status
     */
    public void updateChannelsFromUpnp(@Nullable JsonObject status) {
        if (status == null) {
            return;
        }

        try {
            logger.debug("[{}] Processing UPnP update", deviceId);
            updatePlaybackChannels(status, "UPnP");
            updateDeviceChannels(status, "UPnP");
        } catch (RuntimeException e) {
            logger.warn("[{}] Error processing UPnP update: {}", deviceId, e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] UPnP update error details:", deviceId, e);
            }
        }
    }

    /**
     * Updates playback and device channels from HTTP data.
     *
     * @param status The HTTP status
     */
    public void updateChannelsFromHttp(@Nullable JsonObject status) {
        if (status == null || upnpSubscriptionActive) {
            logger.debug("[{}] Skipping HTTP update: status={}, subscription={}", deviceId,
                    status == null ? "null" : "valid", upnpSubscriptionActive);
            return;
        }

        try {
            logger.debug("[{}] Processing HTTP update", deviceId);
            updatePlaybackChannels(status, "HTTP");
            updateDeviceChannels(status, "HTTP");
        } catch (RuntimeException e) {
            logger.warn("[{}] Error processing HTTP update: {}", deviceId, e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] HTTP update error details:", deviceId, e);
            }
        }
    }

    /**
     * Sets the UPnP subscription state
     *
     * @param active true if UPnP subscriptions are active, false otherwise
     */
    public void setUpnpSubscriptionState(boolean active) {
        this.upnpSubscriptionActive = active;
        logger.debug("[{}] UPnP subscription state changed to: {}", deviceId, active);
    }

    /**
     * Gets the current UPnP subscription state
     *
     * @return true if UPnP subscriptions are active, false otherwise
     */
    public boolean isUpnpSubscriptionActive() {
        return upnpSubscriptionActive;
    }

    /**
     * Updates playback-related channels based on device status.
     *
     * @param status The device status
     * @param source The source of the update (UPnP or HTTP)
     */
    private void updatePlaybackChannels(@Nullable JsonObject status, String source) {
        if (status == null) {
            return;
        }

        logger.debug("[{}] Updating playback channels (source: {})", deviceId, source);

        try {
            updatePlaybackStatus(status);
            updateMetadata(status);
            updateVolumeAndMute(status);
            updatePlaybackMode(status);
        } catch (RuntimeException e) {
            logger.warn("[{}] Error updating playback channels: {}", deviceId, e.getMessage());
        }
    }

    private void updatePlaybackStatus(JsonObject status) {
        if (status.has("status")) {
            String playStatus = getAsString(status, "status");
            updateState(CHANNEL_CONTROL,
                    "play".equalsIgnoreCase(playStatus) ? PlayPauseType.PLAY : PlayPauseType.PAUSE);
        }
    }

    private void updateMetadata(JsonObject status) {
        if (status.has("Title")) {
            updateState(CHANNEL_TITLE, new StringType(getAsString(status, "Title")));
        }
        if (status.has("Artist")) {
            updateState(CHANNEL_ARTIST, new StringType(getAsString(status, "Artist")));
        }
        if (status.has("Album")) {
            updateState(CHANNEL_ALBUM, new StringType(getAsString(status, "Album")));
        }
        if (status.has("AlbumArt")) {
            updateState(CHANNEL_ALBUM_ART, new StringType(getAsString(status, "AlbumArt")));
        }
    }

    private void updateVolumeAndMute(JsonObject status) {
        if (status.has("vol")) {
            int volume = getAsInt(status, "vol", 0);
            updateState(CHANNEL_VOLUME, new PercentType(volume));
        }
        if (status.has("mute")) {
            boolean muted = getAsBoolean(status, "mute");
            updateState(CHANNEL_MUTE, OnOffType.from(muted));
        }
    }

    private void updatePlaybackMode(JsonObject status) {
        if (status.has("loop")) {
            int loopMode = getAsInt(status, "loop", 0);
            boolean shuffle = (loopMode == 2 || loopMode == 3);
            boolean repeat = (loopMode == 0 || loopMode == 2);
            updateState(LinkPlayBindingConstants.CHANNEL_SHUFFLE, OnOffType.from(shuffle));
            updateState(LinkPlayBindingConstants.CHANNEL_REPEAT, OnOffType.from(repeat));
        }
    }

    /**
     * Updates device-related channels based on device status.
     *
     * @param status The device status
     * @param source The source of the update (UPnP or HTTP)
     */
    private void updateDeviceChannels(@Nullable JsonObject status, String source) {
        if (status == null) {
            return;
        }

        logger.debug("[{}] Updating device channels (source: {})", deviceId, source);

        try {
            // Handle all device channel updates directly here
            // Note: Currently no device-specific channels to update
            logger.debug("[{}] Device channels updated", deviceId);
        } catch (RuntimeException e) {
            logger.warn("[{}] Error updating device channels: {}", deviceId, e.getMessage());
        }
    }

    /**
     * Handles communication errors by updating thing status.
     */
    private void handleCommunicationError(Throwable error) {
        String message = error.getMessage();
        if (message == null) {
            message = "Communication error: " + error.getClass().getSimpleName();
        }
        handleStatusUpdate(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, message);
    }

    /**
     * Handles commands sent to the device.
     */
    public void handleCommand(String channelId, Command command) {
        logger.debug("[{}] Handling command {} for channel {}", deviceId, command, channelId);

        try {
            switch (channelId) {
                case CHANNEL_CONTROL -> {
                    String cmd = switch (command.toString().toLowerCase()) {
                        case "play" -> "setPlayerCmd:play";
                        case "pause" -> "setPlayerCmd:pause";
                        case "stop" -> "setPlayerCmd:stop";
                        default -> {
                            logger.debug("[{}] Unsupported control command: {}", deviceId, command);
                            yield "";
                        }
                    };
                    if (!cmd.isEmpty()) {
                        httpManager.sendCommandWithRetry(ipAddress, cmd).exceptionally(error -> {
                            handleCommunicationError(error);
                            return "";
                        });
                    }
                }
                case CHANNEL_VOLUME -> {
                    try {
                        int volume = Integer.parseInt(command.toString());
                        if (volume >= 0 && volume <= 100) {
                            String cmd = "setPlayerCmd:vol:" + volume;
                            httpManager.sendCommandWithRetry(ipAddress, cmd).exceptionally(error -> {
                                handleCommunicationError(error);
                                return "";
                            });
                        } else {
                            logger.warn("[{}] Volume value out of range (0-100): {}", deviceId, volume);
                        }
                    } catch (NumberFormatException e) {
                        logger.warn("[{}] Invalid volume value: {}", deviceId, command);
                    }
                }
                case CHANNEL_MUTE -> {
                    String cmd = "setPlayerCmd:mute:" + ("ON".equalsIgnoreCase(command.toString()) ? "1" : "0");
                    httpManager.sendCommandWithRetry(ipAddress, cmd).exceptionally(error -> {
                        handleCommunicationError(error);
                        return "";
                    });
                }
                default -> logger.debug("[{}] Channel {} not handled by device manager", deviceId, channelId);
            }
        } catch (RuntimeException e) {
            handleCommunicationError(e);
        }
    }

    /**
     * Gets diagnostic information about the device manager state.
     *
     * @return A string containing diagnostic information
     */
    public String getDiagnostics() {
        Thing thing = getThing();
        String bridgeId = Optional.ofNullable(thing.getBridgeUID()).map(uid -> uid.toString()).orElse("None");
        String firmware = thing.getProperties().getOrDefault("firmwareVersion", "Unknown");
        String statusDetail = thing.getStatusInfo().getStatusDetail().toString();
        String pollingStatus = Optional.ofNullable(pollingJob).map(job -> !job.isCancelled() ? "Active" : "Inactive")
                .orElse("Inactive");

        return String.format("""
                Device Diagnostics:
                - Device ID: %s
                - IP Address: %s
                - Thing Status: %s
                - Thing Status Detail: %s
                - UPnP Subscription: %s
                - Polling Active: %s
                - Failed Poll Count: %d
                - Bridge: %s
                - Firmware: %s
                """, deviceId, ipAddress, thing.getStatus(), statusDetail,
                upnpSubscriptionActive ? "Active" : "Inactive", pollingStatus, failedPollCount, bridgeId, firmware);
    }

    /**
     * Disposes of the device manager resources.
     */
    public void dispose() {
        logger.debug("[{}] Disposing device manager", deviceId);
        try {
            stopPolling();
            groupManager.dispose();
            upnpManager.unregister();
            httpManager.dispose();

            failedPollCount = 0;
            upnpSubscriptionActive = false;

            logger.debug("[{}] Device manager disposed successfully", deviceId);
        } catch (RuntimeException e) {
            logger.warn("[{}] Error during device manager disposal: {}", deviceId, e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] Disposal error details:", deviceId, e);
            }
        }
    }

    /**
     * Triggers an immediate poll of the device status.
     */
    public void poll() {
        try {
            httpManager.sendCommandWithRetry(ipAddress, "getPlayerStatus").thenAccept(response -> {
                updateDeviceStatusBasedOnHttp(true);
                logger.debug("[{}] Polling response: {}", deviceId, response);
                try {
                    JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
                    updateChannelsFromHttp(jsonResponse);
                } catch (Exception e) {
                    logger.warn("[{}] Failed to parse player status response: {}", deviceId, e.getMessage());
                }
            }).exceptionally(error -> {
                logger.warn("[{}] Failed to poll player status: {}", deviceId, error.getMessage());
                updateDeviceStatusBasedOnHttp(false);
                return null;
            });
        } catch (RuntimeException e) {
            logger.warn("[{}] Error during polling execution: {}", deviceId, e.getMessage());
            updateDeviceStatusBasedOnHttp(false);
        }
    }

    /**
     * Handles UPnP value updates from subscribed services
     *
     * @param variable The UPnP state variable that changed
     * @param value The new value
     * @param service The UPnP service that sent the update
     */
    public void handleUpnpValue(String variable, String value, String service) {
        try {
            logger.debug("[{}] Processing UPnP update - {}={} from {}", deviceId, variable, value, service);

            switch (service) {
                case "urn:schemas-upnp-org:service:AVTransport:1" -> handleAVTransportUpdate(variable, value);
                case "urn:schemas-upnp-org:service:RenderingControl:1" -> handleRenderingControlUpdate(variable, value);
                default -> logger.debug("[{}] Ignoring update from unsupported service: {}", deviceId, service);
            }
        } catch (RuntimeException e) {
            logger.warn("[{}] Error processing UPnP update: {}", deviceId, e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] UPnP update error details:", deviceId, e);
            }
        }
    }

    private void handleAVTransportUpdate(String variable, String value) {
        switch (variable) {
            case "TransportState" -> updateState(CHANNEL_CONTROL,
                    "PLAYING".equalsIgnoreCase(value) ? PlayPauseType.PLAY : PlayPauseType.PAUSE);
            case "CurrentTrackMetaData" -> updateMetadataFromUpnp(value);
            default -> logger.trace("[{}] Unhandled AVTransport variable: {}", deviceId, variable);
        }
    }

    private void handleRenderingControlUpdate(String variable, String value) {
        switch (variable) {
            case "Volume" -> {
                try {
                    updateState(CHANNEL_VOLUME, new PercentType(value));
                } catch (NumberFormatException e) {
                    logger.warn("[{}] Invalid volume value from UPnP: {}", deviceId, value);
                }
            }
            case "Mute" -> updateState(CHANNEL_MUTE, OnOffType.from("1".equals(value)));
            default -> logger.trace("[{}] Unhandled RenderingControl variable: {}", deviceId, variable);
        }
    }

    /**
     * Gets the thing's current status.
     *
     * @return The current thing status
     */
    public ThingStatus getThingStatus() {
        return thingHandler.getThing().getStatus();
    }

    /**
     * Gets the thing's current status detail.
     *
     * @return The current thing status detail
     */
    public ThingStatusDetail getThingStatusDetail() {
        return thingHandler.getThing().getStatusInfo().getStatusDetail();
    }

    /**
     * Gets the thing instance.
     *
     * @return The thing instance
     */
    public Thing getThing() {
        return thingHandler.getThing();
    }

    /**
     * Updates metadata channels from UPnP CurrentTrackMetaData
     *
     * @param metadata The DIDL-Lite metadata XML string
     */
    private void updateMetadataFromUpnp(String metadata) {
        try {
            // Basic metadata handling for now - just log it
            logger.debug("[{}] Received UPnP metadata: {}", deviceId, metadata);
            // We'll parse and handle the DIDL metadata in a future update
        } catch (RuntimeException e) {
            logger.warn("[{}] Error processing UPnP metadata: {}", deviceId, e.getMessage());
        }
    }
}
