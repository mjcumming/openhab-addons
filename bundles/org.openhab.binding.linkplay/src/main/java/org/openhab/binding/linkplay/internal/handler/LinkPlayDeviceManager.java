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
package org.openhab.binding.linkplay.internal.handler;

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.LinkPlayBindingConstants;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpClient;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpManager;
import org.openhab.binding.linkplay.internal.upnp.LinkPlayUpnpManager;
import org.openhab.binding.linkplay.internal.utils.HexConverter;
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
 * The {@link LinkPlayDeviceManager} manages both HTTP polls and UPnP events,
 * updating channels or Thing status accordingly via the {@link LinkPlayThingHandler}.
 * It preserves multiroom references, if any, and includes all original methods.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayDeviceManager {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayDeviceManager.class);

    private final LinkPlayThingHandler thingHandler;
    private final LinkPlayHttpManager httpManager;
    private final LinkPlayUpnpManager upnpManager;
    private final LinkPlayConfiguration config;

    private final String deviceId;
    private final String deviceUDN; // from config or discovered

    private boolean upnpSubscriptionActive = false;
    private int failedHttpPollCount = 0;
    private static final int MAX_OFFLINE_COUNT = 3;

    /**
     * Constructs the DeviceManager with references to the ThingHandler, config, and HTTP/UPnP managers.
     */
    public LinkPlayDeviceManager(LinkPlayThingHandler thingHandler, LinkPlayConfiguration config,
            LinkPlayHttpClient httpClient, UpnpIOService upnpIOService) {
        this.thingHandler = thingHandler;
        this.config = config;
        this.deviceId = thingHandler.getThing().getUID().getId();
        this.deviceUDN = config.getUdn();

        // Create the HttpManager with full functionality (including sendCommandWithRetry)
        this.httpManager = new LinkPlayHttpManager(httpClient, this, config, deviceId);

        // Create the UpnpManager
        this.upnpManager = new LinkPlayUpnpManager(upnpIOService, this, deviceId);

        logger.trace("[{}] DeviceManager created with config: {}", deviceId, config);
    }

    /**
     * Called by the ThingHandler to initialize device logic.
     */
    public void initialize() {
        logger.debug("[{}] Initializing DeviceManager...", deviceId);

        // Start HTTP polling
        httpManager.startPolling();

        // If we already have a UDN from config, register UPnP
        if (!deviceUDN.isEmpty()) {
            logger.debug("[{}] Found UDN in config => {}. Registering UPnP now.", deviceId, deviceUDN);
            upnpManager.register(deviceUDN);
        } else {
            logger.trace("[{}] No UDN in config yet; will rely on HTTP to discover and register UPnP later.", deviceId);
        }

        // Mark device as ONLINE initially
        handleStatusUpdate(ThingStatus.ONLINE);
    }

    /**
     * Updates channels from the HTTP polling response.
     * Retains all relevant logic, with fix for repeat/shuffle on/off and albumArt from UPnP only.
     */
    public void updateChannelsFromHttp(JsonObject status) {
        logger.trace("[{}] updateChannelsFromHttp => JSON status: {}", deviceId, status);

        // Reset failure count on successful poll
        failedHttpPollCount = 0;

        // Possibly discover new UDN from the JSON
        String discoveredUdn = parseUdnFromHttp(status);
        if (discoveredUdn != null && !discoveredUdn.isEmpty()) {
            if (config.getUdn().isEmpty() || !config.getUdn().equals(discoveredUdn)) {
                logger.info("[{}] Discovered new UDN via HTTP: {}", deviceId, discoveredUdn);
                thingHandler.updateUdnInConfig(discoveredUdn);
                upnpManager.register(discoveredUdn);
            }
        }

        // Playback status
        if (status.has("status")) {
            String playStatus = getAsString(status, "status");
            State controlState = "play".equalsIgnoreCase(playStatus) ? PlayPauseType.PLAY : PlayPauseType.PAUSE;
            updateState(LinkPlayBindingConstants.CHANNEL_CONTROL, controlState);
        }

        // Use centralized method for title/artist/album updates
        updateMediaInfoChannels(status);

        // Volume
        if (status.has("vol")) {
            int vol = getAsInt(status, "vol", 0);
            updateState(LinkPlayBindingConstants.CHANNEL_VOLUME, new PercentType(vol));
        }

        // Mute
        if (status.has("mute")) {
            boolean muted = getAsBoolean(status, "mute");
            updateState(LinkPlayBindingConstants.CHANNEL_MUTE, OnOffType.from(muted));
        }

        // Shuffle / Repeat
        // LinkPlay doc suggests "random" might be used for shuffle, "loop" for repeat modes
        // We interpret nonzero "loop" as repeat=ON.
        if (status.has("loop")) {
            int loopVal = getAsInt(status, "loop", 0);
            boolean repeating = (loopVal != 0);
            updateState(LinkPlayBindingConstants.CHANNEL_REPEAT, OnOffType.from(repeating));
        }
        if (status.has("random")) {
            boolean shuffleOn = getAsBoolean(status, "random");
            updateState(LinkPlayBindingConstants.CHANNEL_SHUFFLE, OnOffType.from(shuffleOn));
        }

        // Not all devices have a DeviceName
        if (status.has("DeviceName")) {
            String name = status.get("DeviceName").getAsString();
            updateState(LinkPlayBindingConstants.CHANNEL_DEVICE_NAME, new StringType(name));
        }

        // Not all devices have a GroupName
        if (status.has("GroupName")) {
            String gName = status.get("GroupName").getAsString();
            updateState(LinkPlayBindingConstants.CHANNEL_GROUP_NAME, new StringType(gName));
        }

        // LinkPlay's getPlayerStatus does not provide album art => no code needed here to set albumArt

        // If we were offline, mark us back online
        if (thingHandler.getThing().getStatus() != ThingStatus.ONLINE) {
            handleStatusUpdate(ThingStatus.ONLINE);
        }
    }

    /**
     * Called by the HTTP manager after a failed poll, retains original logic.
     */
    public void handleHttpPollFailure(Throwable error) {
        failedHttpPollCount++;
        if (failedHttpPollCount >= MAX_OFFLINE_COUNT) {
            logger.warn("[{}] HTTP poll failure #{} => {}", deviceId, failedHttpPollCount, error.getMessage());
            handleStatusUpdate(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "No response from device after " + failedHttpPollCount + " attempts");
        } else {
            logger.trace("[{}] HTTP poll failure #{} => {}", deviceId, failedHttpPollCount, error.getMessage());
        }
    }

    /**
     * Handles commands from the openHAB framework (UI, rules, etc.).
     * Passes them to the HTTP manager for formatting and sending.
     */
    public void handleCommand(String channelId, Command command) {
        logger.trace("[{}] Handling command '{}' for channel '{}'", deviceId, command, channelId);

        // Check if command is REFRESH
        if ("REFRESH".equalsIgnoreCase(command.toString())) {
            // Decide which refresh logic to use
            switch (channelId) {
                case LinkPlayBindingConstants.CHANNEL_VOLUME:
                case LinkPlayBindingConstants.CHANNEL_MUTE:
                case LinkPlayBindingConstants.CHANNEL_CONTROL:
                case LinkPlayBindingConstants.CHANNEL_ARTIST:
                case LinkPlayBindingConstants.CHANNEL_ALBUM:
                case LinkPlayBindingConstants.CHANNEL_TITLE:
                    // For playback-oriented channels, we do a partial or one-off call to getPlayerStatus
                    logger.trace("[{}] REFRESH => one-off call to getPlayerStatus for channel '{}'", deviceId,
                            channelId);
                    httpManager.refreshPlayerStatus();
                    break;

                case LinkPlayBindingConstants.CHANNEL_ROLE:
                case LinkPlayBindingConstants.CHANNEL_MASTER_IP:
                case LinkPlayBindingConstants.CHANNEL_SLAVE_IPS:
                    // For multiroom info, we want getStatusEx or a different endpoint
                    logger.trace("[{}] REFRESH => calling getStatusEx for multiroom channel '{}'", deviceId, channelId);
                    httpManager.refreshMultiroomStatus();
                    break;

                // For any read-only channels you do not want to fetch at all, just do nothing:
                default:
                    logger.trace("[{}] REFRESH => ignoring read-only or unmapped channel '{}'", deviceId, channelId);
                    break;
            }

            // Return here so we do NOT pass REFRESH to the HTTP manager's normal command sending
            return;
        }

        // Otherwise, normal commands:
        httpManager.sendChannelCommand(channelId, command);
    }

    /**
     * Called by the UpnpManager to indicate subscription success/failure, unchanged from original.
     */
    public void setUpnpSubscriptionState(boolean active) {
        logger.debug("[{}] setUpnpSubscriptionState => {}", deviceId, active);
        upnpSubscriptionActive = active;
    }

    public boolean isUpnpSubscriptionActive() {
        return upnpSubscriptionActive;
    }

    /**
     * Cleanup if the Thing is disposed or removed. Stop polling & Upnp subscriptions.
     */
    public void dispose() {
        logger.debug("[{}] Disposing DeviceManager", deviceId);
        httpManager.stopPolling();
        upnpManager.dispose();
    }

    /**
     * Used by the UPnP Manager to update playback state.
     */
    public void updatePlaybackState(String state) {
        logger.trace("[{}] Updating playback state (UPnP) => {}", deviceId, state);
        State controlState = "play".equalsIgnoreCase(state) ? PlayPauseType.PLAY : PlayPauseType.PAUSE;
        updateState(LinkPlayBindingConstants.CHANNEL_CONTROL, controlState);
    }

    /**
     * Used by the UPnP manager to update metadata from the event; includes album art (URL) from UPnP.
     */
    public void updateMetadata(Map<String, String> metadata) {
        logger.trace("[{}] Updating metadata (UPnP) => {}", deviceId, metadata);
        if (metadata.containsKey("Title")) {
            updateState(LinkPlayBindingConstants.CHANNEL_TITLE, new StringType(metadata.get("Title")));
        }
        if (metadata.containsKey("Artist")) {
            updateState(LinkPlayBindingConstants.CHANNEL_ARTIST, new StringType(metadata.get("Artist")));
        }
        if (metadata.containsKey("Album")) {
            updateState(LinkPlayBindingConstants.CHANNEL_ALBUM, new StringType(metadata.get("Album")));
        }
        if (metadata.containsKey("AlbumArt")) {
            updateState(LinkPlayBindingConstants.CHANNEL_ALBUM_ART, new StringType(metadata.get("AlbumArt")));
        }
    }

    /**
     * Transport URI from UPnP, preserved from original code.
     */
    public void updateTransportUri(String uri) {
        logger.trace("[{}] Updating transport URI => {}", deviceId, uri);
    }

    /**
     * Current track duration from UPnP events, original structure preserved.
     */
    public void updateDuration(String duration) {
        logger.trace("[{}] Updating media duration => {}", deviceId, duration);
    }

    /**
     * Volume from UPnP events, same logic as from HTTP (just a different source).
     */
    public void updateVolume(String volume) {
        logger.trace("[{}] Updating volume (UPnP) => {}", deviceId, volume);
        try {
            int vol = Integer.parseInt(volume);
            updateState(LinkPlayBindingConstants.CHANNEL_VOLUME, new PercentType(vol));
        } catch (NumberFormatException e) {
            logger.warn("[{}] Invalid volume from UPnP => {}", deviceId, volume);
        }
    }

    /**
     * Mute from UPnP events.
     */
    public void updateMute(boolean mute) {
        logger.trace("[{}] Updating mute state (UPnP) => {}", deviceId, mute);
        updateState(LinkPlayBindingConstants.CHANNEL_MUTE, OnOffType.from(mute));
    }

    // ---------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------

    private void updateState(String channelId, State state) {
        logger.trace("[{}] updateState => channel={}, state={}", deviceId, channelId, state);
        thingHandler.handleStateUpdate(channelId, state);
    }

    private void handleStatusUpdate(ThingStatus status) {
        thingHandler.handleStatusUpdate(status);
    }

    private void handleStatusUpdate(ThingStatus status, ThingStatusDetail detail, String msg) {
        thingHandler.handleStatusUpdate(status, detail, msg);
    }

    /**
     * Basic example to parse UDN from an HTTP JSON, same logic as original code.
     */
    private @Nullable String parseUdnFromHttp(JsonObject json) {
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
            logger.trace("[{}] Failed to get string for key='{}': {}", deviceId, key, e.getMessage());
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
            logger.trace("[{}] Failed to get int for key='{}': {}", deviceId, key, e.getMessage());
            return defaultValue;
        }
    }

    private boolean getAsBoolean(JsonObject obj, String key) {
        try {
            if (!obj.has(key) || obj.get(key).isJsonNull()) {
                return false;
            }
            String val = obj.get(key).getAsString();
            return "true".equalsIgnoreCase(val) || "1".equals(val) || "on".equalsIgnoreCase(val);
        } catch (Exception e) {
            logger.trace("[{}] Failed to get boolean for key='{}': {}", deviceId, key, e.getMessage());
            return false;
        }
    }

    private void updateMediaInfoChannels(JsonObject json) {
        // For title, artist, album - decode from hex if present
        if (json.has("Title")) {
            String hexTitle = json.get("Title").getAsString();
            String title = HexConverter.hexToString(hexTitle);
            updateState(LinkPlayBindingConstants.CHANNEL_TITLE, new StringType(title));
        }

        if (json.has("Artist")) {
            String hexArtist = json.get("Artist").getAsString();
            String artist = HexConverter.hexToString(hexArtist);
            updateState(LinkPlayBindingConstants.CHANNEL_ARTIST, new StringType(artist));
        }

        if (json.has("Album")) {
            String hexAlbum = json.get("Album").getAsString();
            String album = HexConverter.hexToString(hexAlbum);
            updateState(LinkPlayBindingConstants.CHANNEL_ALBUM, new StringType(album));
        }
    }
}
