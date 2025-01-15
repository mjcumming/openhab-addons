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

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpClient;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpManager;
import org.openhab.binding.linkplay.internal.upnp.LinkPlayUpnpManager;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.PlayPauseType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

/**
 * Revised LinkPlayDeviceManager that relies on the HttpManager for
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

    private final LinkPlayThingHandler thingHandler;
    private final LinkPlayHttpManager httpManager;
    private final LinkPlayUpnpManager upnpManager;

    private final LinkPlayConfiguration config;

    private final String deviceId;
    private boolean upnpSubscriptionActive = false;
    private int failedHttpPollCount = 0;
    private static final int MAX_OFFLINE_COUNT = 3;

    // Removed the unused `failedHttpPolls` and `MAX_FAILED_HTTP_POLLS`

    private final String deviceUDN;

    public LinkPlayDeviceManager(LinkPlayThingHandler thingHandler, LinkPlayConfiguration config,
            LinkPlayHttpClient httpClient, UpnpIOService upnpIOService) {
        this.thingHandler = thingHandler;
        this.config = config;
        this.deviceId = thingHandler.getThing().getUID().getId();
        this.deviceUDN = config.getUdn();
        this.httpManager = new LinkPlayHttpManager(httpClient, this, config);
        this.upnpManager = new LinkPlayUpnpManager(upnpIOService, this, deviceId);
        logger.debug("[{}] DeviceManager created with config: {}", deviceId, config);
    }

    /**
     * Called by the ThingHandler to initialize device logic.
     */
    public void initialize() {
        logger.debug("[{}] Initializing DeviceManager...", deviceId);

        // Start HTTP polling
        httpManager.startPolling();

        // If we already have a UDN from config, register UPnP
        String existingUdn = config.getUdn();
        if (!existingUdn.isEmpty()) { // no need for null check if guaranteed non-null
            logger.debug("[{}] We already have UDN '{}', registering UPnP now", deviceId, existingUdn);
            upnpManager.register(existingUdn);
        } else {
            logger.debug("[{}] No UDN yet, will rely on HTTP first and register UPnP later", deviceId);
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

        if (upnpSubscriptionActive) {
            logger.debug("[{}] UPnP subscription is active => we can skip or partially skip HTTP updates if desired",
                    deviceId);
            // For demonstration, let's do full updates anyway.
        }

        // Parse "status"
        if (status.has("status")) {
            String playStatus = getAsString(status, "status");
            updateState(CHANNEL_CONTROL,
                    "play".equalsIgnoreCase(playStatus) ? PlayPauseType.PLAY : PlayPauseType.PAUSE);
        }

        // Title, Artist, Album, etc.
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
            boolean shuffle = (loopMode == 2 || loopMode == 3);
            boolean repeat = (loopMode == 0 || loopMode == 2);
            updateState(CHANNEL_SHUFFLE, OnOffType.from(shuffle));
            updateState(CHANNEL_REPEAT, OnOffType.from(repeat));
        }

        // Reset offline count
        failedHttpPollCount = 0;
        if (thingHandler.getThing().getStatus() != ThingStatus.ONLINE) {
            handleStatusUpdate(ThingStatus.ONLINE);
        }
    }

    /**
     * Called by the HTTP manager after a failed poll.
     */
    public void handleHttpPollFailure(Throwable error) {
        failedHttpPollCount++;
        logger.warn("[{}] HTTP poll failure #{}: {}", deviceId, failedHttpPollCount, error.getMessage());

        if (failedHttpPollCount >= MAX_OFFLINE_COUNT) {
            handleStatusUpdate(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Device unreachable after " + failedHttpPollCount + " attempts");
        }
    }

    /**
     * Handles commands from the Thing handler.
     * 
     * @param channelId The channel ID without group
     * @param command The command to handle
     */
    public void handleCommand(String channelId, Command command) {
        logger.debug("[{}] Handling command {} for channel {}", deviceId, command, channelId);
        this.httpManager.sendChannelCommand(channelId, command);
    }

    /**
     * Called by the UpnpManager to indicate subscription success/failure.
     */
    public void setUpnpSubscriptionState(boolean active) {
        logger.debug("[{}] setUpnpSubscriptionState => {}", deviceId, active);
        this.upnpSubscriptionActive = active;
    }

    public boolean isUpnpSubscriptionActive() {
        return upnpSubscriptionActive;
    }

    /**
     * Cleanup.
     */
    public void dispose() {
        logger.debug("[{}] Disposing device manager", deviceId);
        httpManager.stopPolling();
        upnpManager.dispose();
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
     * Example logic to parse UDN from an HTTP response.
     * Adjust as needed based on actual device JSON.
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
        if (metadata.containsKey("Title")) {
            updateState(CHANNEL_TITLE, new StringType(metadata.get("Title")));
        }
        if (metadata.containsKey("Artist")) {
            updateState(CHANNEL_ARTIST, new StringType(metadata.get("Artist")));
        }
        if (metadata.containsKey("Album")) {
            updateState(CHANNEL_ALBUM, new StringType(metadata.get("Album")));
        }
        if (metadata.containsKey("AlbumArt")) {
            updateState(CHANNEL_ALBUM_ART, new StringType(metadata.get("AlbumArt")));
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
}
