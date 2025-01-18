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
 */
package org.openhab.binding.linkplay.internal.handler;

import static org.openhab.binding.linkplay.internal.LinkPlayBindingConstants.*;

import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpClient;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpManager;
import org.openhab.binding.linkplay.internal.metadata.LinkPlayMetadataService;
import org.openhab.binding.linkplay.internal.uart.LinkPlayUartManager;
import org.openhab.binding.linkplay.internal.upnp.LinkPlayUpnpManager;
import org.openhab.binding.linkplay.internal.utils.HexConverter;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.PlayPauseType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

/**
 * LinkPlayDeviceManager that relies on the HttpManager for
 * all polling and JSON parsing. It updates channels or Thing status
 * based on callbacks from HttpManager and UpnpManager.
 * 
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayDeviceManager {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayDeviceManager.class);

    // Channels from your binding constants
    private static final String CHANNEL_CONTROL = "control";
    private static final String CHANNEL_TITLE = "title";
    private static final String CHANNEL_ARTIST = "artist";
    private static final String CHANNEL_ALBUM = "album";
    private static final String CHANNEL_ALBUM_ART = "albumArt";
    private static final String CHANNEL_VOLUME = "volume";
    private static final String CHANNEL_MUTE = "mute";
    private static final String CHANNEL_SHUFFLE = "shuffle";
    private static final String CHANNEL_REPEAT = "repeat";
    private static final String CHANNEL_DURATION = "duration";
    private static final String CHANNEL_POSITION = "position";

    private final LinkPlayThingHandler thingHandler;
    private final LinkPlayHttpManager httpManager;
    private final LinkPlayUpnpManager upnpManager;
    private @Nullable LinkPlayUartManager uartManager;

    private final LinkPlayConfiguration config;

    private final String deviceId;
    private boolean upnpSubscriptionActive = false;
    private int failedHttpPollCount = 0;
    private static final int MAX_OFFLINE_COUNT = 3;

    // Removed the unused `failedHttpPolls` and `MAX_FAILED_HTTP_POLLS`

    private final String deviceUDN;

    private final LinkPlayMetadataService metadataService;
    private @Nullable String lastArtist;
    private @Nullable String lastTitle;

    // Source mapping
    private static final Map<Integer, String> SOURCE_MAPPING = Map.of(0, "WiFi", 1, "Bluetooth", 2, "Line-In", 3, "USB",
            4, "TF Card", 10, "Optical", 11, "RCA");

    public LinkPlayDeviceManager(LinkPlayThingHandler thingHandler, LinkPlayConfiguration config,
            LinkPlayHttpClient httpClient, UpnpIOService upnpIOService) {
        this.thingHandler = thingHandler;
        this.config = config;
        this.deviceId = thingHandler.getThing().getUID().getId();
        this.deviceUDN = config.getUdn();
        this.httpManager = new LinkPlayHttpManager(httpClient, this, config, deviceId);
        this.upnpManager = new LinkPlayUpnpManager(upnpIOService, this, deviceId);
        this.uartManager = new LinkPlayUartManager(config.getIpAddress(), config.getUdn(), this);
        this.metadataService = new LinkPlayMetadataService(httpClient, deviceId);
        logger.debug("[{}] DeviceManager created with config: {}", deviceId, config);
    }

    /**
     * Called by the ThingHandler to initialize device logic.
     */
    public void initialize() {
        logger.trace("[{}] Initializing DeviceManager...", deviceId);

        // If we already have a UDN from config, register UPnP
        String existingUdn = config.getUdn();
        if (!existingUdn.isEmpty()) {
            logger.trace("[{}] We already have UDN '{}', registering UPnP now", deviceId, existingUdn);
            upnpManager.register(existingUdn);
        } else {
            logger.trace("[{}] No UDN yet, will rely on HTTP first and register UPnP later", deviceId);
        }

        // Mark device as ONLINE initially
        handleStatusUpdate(ThingStatus.ONLINE);
    }

    /**
     * Called by the HTTP manager whenever a successful poll returns JSON data.
     */
    public void updateChannelsFromHttp(JsonObject status) {
        // Possibly discover a new UDN from the JSON
        String discoveredUdn = parseUdnFromHttp(status);
        if (discoveredUdn != null && !discoveredUdn.isEmpty()) {
            if (config.getUdn().isEmpty() || !config.getUdn().equals(discoveredUdn)) {
                logger.debug("[{}] Discovered new UDN via HTTP: {}", deviceId, discoveredUdn);
                // Delegate to the thing handler to store in config
                thingHandler.updateUdnInConfig(discoveredUdn);
                // Now register with Upnp if not already
                upnpManager.register(discoveredUdn);
            }
        }

        // Parse "status"
        if (status.has("status")) {
            String playStatus = getAsString(status, "status");
            updateState(CHANNEL_CONTROL,
                    "play".equalsIgnoreCase(playStatus) ? PlayPauseType.PLAY : PlayPauseType.PAUSE);
        }

        // Title and Artist (which will check metadata)
        String newTitle = status.has("Title") ? HexConverter.hexToString(getAsString(status, "Title")) : null;
        String newArtist = status.has("Artist") ? HexConverter.hexToString(getAsString(status, "Artist")) : null;
        updateTitleAndArtist(newTitle, newArtist);

        // Album (separate since it doesn't affect metadata)
        if (status.has("Album")) {
            updateState(CHANNEL_ALBUM, new StringType(HexConverter.hexToString(getAsString(status, "Album"))));
        }

        // Volume + Mute
        if (status.has("vol")) {
            int vol = getAsInt(status, "vol", 0);
            updateState(CHANNEL_VOLUME, new PercentType(vol));
        }
        if (status.has("mute")) {
            boolean muted = getAsBoolean(status, "mute");
            updateState(CHANNEL_MUTE, OnOffType.from(muted));
        }

        // Shuffle/Repeat
        if (status.has("loop")) {
            int loopMode = getAsInt(status, "loop", 0);
            boolean shuffle = (loopMode == 2 || loopMode == 3 || loopMode == 5);
            boolean repeat = (loopMode == 0 || loopMode == 1 || loopMode == 2 || loopMode == 5);

            updateState(CHANNEL_SHUFFLE, OnOffType.from(shuffle));
            updateState(CHANNEL_REPEAT, OnOffType.from(repeat));

            logger.trace("[{}] Updated shuffle={}, repeat={} from loopMode={}", deviceId, shuffle, repeat, loopMode);
        }

        // Duration and Position (already in milliseconds from API)
        if (status.has("duration")) {
            double durationSec = getAsInt(status, "duration", 0) / 1000.0;
            updateState(CHANNEL_DURATION, new QuantityType<>(durationSec, Units.SECOND));
            logger.trace("[{}] Updated duration to {}s", deviceId, durationSec);
        }

        if (status.has("position")) {
            double positionSec = getAsInt(status, "position", 0) / 1000.0;
            updateState(CHANNEL_POSITION, new QuantityType<>(positionSec, Units.SECOND));
            logger.trace("[{}] Updated position to {}s", deviceId, positionSec);
        }

        // Source mapping
        if (status.has("source")) {
            int sourceNum = getAsInt(status, "source", 0);
            String sourceName = SOURCE_MAPPING.getOrDefault(sourceNum, "Unknown(" + sourceNum + ")");
            updateState(CHANNEL_SOURCE, new StringType(sourceName));
            logger.trace("[{}] Updated source to {} ({})", deviceId, sourceName, sourceNum);
        }

        // Reset offline count
        failedHttpPollCount = 0;
        if (thingHandler.getThing().getStatus() != ThingStatus.ONLINE) {
            handleStatusUpdate(ThingStatus.ONLINE);
        }
    }

    /**
     * Handles commands from the Thing handler.
     * 
     * @param channelId The channel ID without group
     * @param command The command to handle
     */
    public void handleCommand(String channelId, Command command) {
        logger.trace("[{}] Handling command {} for channel {}", deviceId, command, channelId);
        this.httpManager.sendChannelCommand(channelId, command);
    }

    /**
     * Called by the UpnpManager to indicate subscription success/failure.
     */
    public void setUpnpSubscriptionState(boolean active) {
        logger.trace("[{}] setUpnpSubscriptionState => {}", deviceId, active);
        this.upnpSubscriptionActive = active;
    }

    public boolean isUpnpSubscriptionActive() {
        return upnpSubscriptionActive;
    }

    /**
     * Cleanup.
     */
    public void dispose() {
        logger.trace("[{}] Disposing device manager", deviceId);

        // Dispose all managers
        httpManager.dispose();
        upnpManager.dispose();
        uartManager.dispose();
        uartManager = null;

        // Clear any cached metadata
        lastArtist = null;
        lastTitle = null;
    }

    // -------------------------------------------------------------------
    // Internal Helpers
    // -------------------------------------------------------------------

    private void updateState(String channelId, State state) {
        thingHandler.handleStateUpdate(channelId, state);
    }

    private void handleStatusUpdate(ThingStatus status) {
        thingHandler.handleStatusUpdate(status);
    }

    private void handleStatusUpdate(ThingStatus status, ThingStatusDetail detail, String msg) {
        thingHandler.handleStatusUpdate(status, detail, msg);
    }

    /**
     * logic to parse UDN from an HTTP response.
     */
    private @Nullable String parseUdnFromHttp(JsonObject json) {
        // e.g. { "udn":"uuid:LinkPlay_ABC123" }
        if (json.has("udn")) {
            String found = getAsString(json, "udn");
            if (!found.isEmpty() && !found.startsWith("uuid:")) {
                found = "uuid:" + found;
            }
            return found;
        }
        return null;
    }

    private String getAsString(JsonObject obj, String key) {
        try {
            if (!obj.has(key) || obj.get(key).isJsonNull()) {
                return "";
            }
            return obj.get(key).getAsString();
        } catch (Exception e) {
            logger.trace("[{}] Failed to get string value for '{}': {}", deviceId, key, e.getMessage());
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
            logger.trace("[{}] Failed to get int value for '{}': {}", deviceId, key, e.getMessage());
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
            return val.equalsIgnoreCase("true") || val.equals("1") || val.equalsIgnoreCase("on");
        } catch (Exception e) {
            logger.trace("[{}] Failed to get boolean value for '{}': {}", deviceId, key, e.getMessage());
            return false;
        }
    }

    public String getDeviceUDN() {
        return deviceUDN;
    }

    /**
     * Updates the playback state of the device.
     *
     * @param state The new playback state.
     */
    public void updatePlaybackState(String state) {
        logger.debug("[{}] Updating playback state to {}", deviceId, state);
        updateState(CHANNEL_CONTROL, state.equalsIgnoreCase("play") ? PlayPauseType.PLAY : PlayPauseType.PAUSE);
    }

    /**
     * Updates the metadata of the currently playing media.
     *
     * @param metadata A map containing metadata key-value pairs.
     */
    public void updateMetadata(Map<String, String> metadata) {
        logger.debug("[{}] Updating metadata: {}", deviceId, metadata);

        // Title and Artist (which will check metadata)
        updateTitleAndArtist(metadata.get("Title"), metadata.get("Artist"));

        // Album (separate since it doesn't affect metadata)
        if (metadata.containsKey("Album")) {
            updateState(CHANNEL_ALBUM, new StringType(metadata.get("Album")));
        }
    }

    /**
     * Updates the transport URI of the device.
     *
     * @param uri The new transport URI.
     */
    public void updateTransportUri(String uri) {
        logger.debug("[{}] Updating transport URI to {}", deviceId, uri);
        // No channel for transport URI yet
    }

    /**
     * Updates the duration of the media.
     *
     * @param duration The duration string.
     */
    public void updateDuration(String duration) {
        logger.debug("[{}] Updating media duration to {}", deviceId, duration);
        // No channel for duration yet
    }

    /**
     * Updates the volume level of the device.
     *
     * @param volume The new volume level.
     */
    public void updateVolume(String volume) {
        logger.debug("[{}] Updating volume to {}", deviceId, volume);
        try {
            int vol = Integer.parseInt(volume);
            updateState(CHANNEL_VOLUME, new PercentType(vol));
        } catch (NumberFormatException e) {
            logger.warn("[{}] Invalid volume value: {}", deviceId, volume);
        }
    }

    /**
     * Updates the mute state of the device.
     *
     * @param mute True to mute, false to unmute.
     */
    public void updateMute(boolean mute) {
        logger.debug("[{}] Updating mute state to {}", deviceId, mute);
        updateState(CHANNEL_MUTE, mute ? OnOffType.ON : OnOffType.OFF);
    }

    /**
     * Updates a channel's state based on UART data.
     *
     * @param channelId The channel to update
     * @param value The new value from UART
     */
    public void updateChannelFromUart(String channelId, int value) {
        logger.warn("[{}] Updating channel {} from UART with value {}", deviceId, channelId, value);

        switch (channelId.toLowerCase()) {
            case "volume":
                updateState(CHANNEL_VOLUME, new PercentType(value));
                logger.warn("[{}] Updated volume channel to {}", deviceId, value);
                break;
            case "mute":
                updateState(CHANNEL_MUTE, value == 1 ? OnOffType.ON : OnOffType.OFF);
                logger.warn("[{}] Updated mute channel to {}", deviceId, value == 1);
                break;
            case "duration":
                // Duration is in seconds, using Number:Time type as defined in XML
                updateState(CHANNEL_DURATION, new QuantityType<>(value, Units.SECOND));
                logger.warn("[{}] Updated duration channel to {}s", deviceId, value);
                break;
            case "position":
                // Position is in seconds, using Number:Time type as defined in XML
                updateState(CHANNEL_POSITION, new QuantityType<>(value, Units.SECOND));
                logger.warn("[{}] Updated position channel to {}s", deviceId, value);
                break;
            default:
                logger.warn("[{}] Unhandled UART channel update: {}={}", deviceId, channelId, value);
        }
    }

    /**
     * Updates title and artist channels and checks if metadata needs to be updated
     */
    private void updateTitleAndArtist(@Nullable String newTitle, @Nullable String newArtist) {
        // Update title and artist channels
        if (newTitle != null) {
            updateState(CHANNEL_TITLE, new StringType(newTitle));
        }
        if (newArtist != null) {
            updateState(CHANNEL_ARTIST, new StringType(newArtist));
        }

        // Only try to fetch album art if either title or artist changed
        if ((newTitle != null && !newTitle.equals(lastTitle)) || (newArtist != null && !newArtist.equals(lastArtist))) {

            // Try to get album art via metadata service
            Optional<String> albumArtUrl = metadataService.retrieveMusicMetadata(newArtist, newTitle);
            if (albumArtUrl.isPresent()) {
                updateState(CHANNEL_ALBUM_ART, new StringType(albumArtUrl.get()));
                logger.debug("[{}] Updated album art URL: {}", deviceId, albumArtUrl.get());
            } else {
                updateState(CHANNEL_ALBUM_ART, new StringType("NOT_FOUND"));
                logger.debug("[{}] No album art found for {}/{}", deviceId, newArtist, newTitle);
            }

            lastTitle = newTitle;
            lastArtist = newArtist;
        }
    }

    /**
     * Handle UART communication errors by updating Thing status
     */
    public void handleUartCommunicationError(String message) {
        logger.warn("[{}] UART Error: {}", deviceId, message);
        // Similar to how we handle UPNP errors, log but don't change Thing status
        // since UART is not critical for core functionality
    }

    /**
     * Send UART command to device
     */
    public @Nullable String sendUartCommand(String command) {
        LinkPlayUartManager manager = uartManager;
        if (manager == null) {
            logger.debug("[{}] Cannot send UART command - manager not initialized", config.getIpAddress());
            return null;
        }
        return manager.sendCommand(command);
    }

    /**
     * Update channels based on extended status response
     */
    public void updateMultiroomChannelsFromHttp(JsonObject json) {
        try {
            MultiroomInfo info = new MultiroomInfo(json);
            String role = info.getRole();

            // Update role channel with proper validation
            if (role != null && !role.isEmpty()) {
                switch (role.toLowerCase()) {
                    case "master":
                        updateState(CHANNEL_ROLE, new StringType("master"));
                        break;
                    case "slave":
                        updateState(CHANNEL_ROLE, new StringType("slave"));
                        break;
                    default:
                        updateState(CHANNEL_ROLE, new StringType("standalone"));
                }
                logger.trace("[{}] Updated multiroom role to {}", deviceId, role);
            } else {
                logger.debug("[{}] No valid role information in status", deviceId);
                updateState(CHANNEL_ROLE, new StringType("standalone"));
            }

            // ... rest of multiroom channel updates ...
        } catch (Exception e) {
            logger.warn("[{}] Error updating multiroom channels: {}", deviceId, e.getMessage());
        }
    }

    /**
     * Handle HTTP communication result
     * 
     * @param success true if communication succeeded, false if it failed
     */
    public void handleCommunicationResult(boolean success) {
        if (success) {
            failedHttpPollCount = 0;
            if (thingHandler.getThing().getStatus() == ThingStatus.OFFLINE) {
                handleStatusUpdate(ThingStatus.ONLINE);
            }
        } else {
            failedHttpPollCount++;
            logger.trace("[{}] Communication failure #{}", deviceId, failedHttpPollCount);

            if (failedHttpPollCount >= MAX_OFFLINE_COUNT) {
                handleStatusUpdate(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Device unreachable after " + failedHttpPollCount + " attempts");
            }
        }
    }

    private void handlePlayerStatus(JsonObject status) {
        try {
            // ... existing status handling code ...

            // Check if track changed by comparing with last known values
            String artist = status.get("Artist").getAsString();
            String title = status.get("Title").getAsString();

            // Use existing updateTitleAndArtist method which already handles metadata
            updateTitleAndArtist(title, artist);

        } catch (Exception e) {
            logger.warn("[{}] Error handling player status: {}", deviceId, e.getMessage());
        }
    }

    /**
     * Updates network-related channels from multiroom status
     */
    public void updateNetworkInfo(JsonObject multiroomStatus) {
        if (multiroomStatus.has("ip")) {
            updateState(CHANNEL_IP_ADDRESS, new StringType(getAsString(multiroomStatus, "ip")));
        }
        if (multiroomStatus.has("mac")) {
            updateState(CHANNEL_MAC_ADDRESS, new StringType(getAsString(multiroomStatus, "mac")));
        }
        if (multiroomStatus.has("wifi_signal")) {
            int signal = getAsInt(multiroomStatus, "wifi_signal", 0);
            updateState(CHANNEL_WIFI_SIGNAL, new QuantityType<>(signal, Units.DECIBEL_MILLIWATTS));
        }
    }
}
