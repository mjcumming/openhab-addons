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
package org.openhab.binding.linkplay.internal;

import static org.openhab.binding.linkplay.internal.LinkPlayBindingConstants.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.binding.linkplay.internal.handler.LinkPlayThingHandler;
import org.openhab.binding.linkplay.internal.metadata.LinkPlayMetadataService;
import org.openhab.binding.linkplay.internal.model.LinkPlayDeviceState;
import org.openhab.binding.linkplay.internal.multiroom.LinkPlayGroupManager;
import org.openhab.binding.linkplay.internal.transport.http.LinkPlayHttpClient;
import org.openhab.binding.linkplay.internal.transport.http.LinkPlayHttpManager;
import org.openhab.binding.linkplay.internal.transport.uart.LinkPlayUartManager;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
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

    private final LinkPlayThingHandler thingHandler;
    private final LinkPlayConfiguration config;
    @SuppressWarnings("unused") // Used by HttpManager and MetadataService
    private final LinkPlayHttpClient httpClient;
    @SuppressWarnings("unused") // Used by UpnpManager
    private final UpnpIOService upnpIOService;
    private final LinkPlayHttpManager httpManager;
    private final LinkPlayGroupManager groupManager;
    private final LinkPlayMetadataService metadataService;
    private final LinkPlayUartManager uartManager;

    private final LinkPlayDeviceState deviceState;

    private String lastArtist = "";
    private String lastTitle = "";

    public LinkPlayDeviceManager(LinkPlayThingHandler thingHandler, LinkPlayConfiguration config,
            LinkPlayHttpClient httpClient, UpnpIOService upnpIOService) {
        this.thingHandler = thingHandler;
        this.config = config;
        this.httpClient = httpClient;
        this.upnpIOService = upnpIOService;

        // Initialize device state from config
        deviceState = new LinkPlayDeviceState();
        deviceState.initializeFromConfig(config);

        // Create managers with simplified dependencies
        this.httpManager = new LinkPlayHttpManager(httpClient, this);
        this.uartManager = new LinkPlayUartManager(this);
        this.metadataService = new LinkPlayMetadataService(httpClient, this);
        this.groupManager = new LinkPlayGroupManager(this);

        logger.debug("[{}] DeviceManager created with config: {}", config.getDeviceName(), config);

        // Clear any cached metadata
        lastArtist = "";
        lastTitle = "";
    }

    /**
     * Called by the ThingHandler to initialize device logic.
     */
    public void initialize() {
        logger.debug("[{}] Initializing DeviceManager...", config.getDeviceName());

        // Start with UNKNOWN status during initialization
        handleStatusUpdate(ThingStatus.UNKNOWN);

        // Start HTTP polling immediately - this is our primary communication method
        httpManager.startPolling();
    }

    /**
     * Initialize additional features like UPnP and metadata services
     */
    public void initializeAdditionalFeatures() {
        // Initialize UPnP if we have a UDN
        String existingUdn = config.getUdn();
        if (!existingUdn.isEmpty()) {
            logger.debug("[{}] UPnP initialized for UDN: {}", config.getDeviceName(), existingUdn);
            // TODO: Implement UPnP initialization
        } else {
            logger.debug("[{}] UPnP manager initialized but waiting for UDN discovery", config.getDeviceName());
        }

        logger.debug("[{}] Additional features initialized", config.getDeviceName());
    }

    /**
     * Handles commands from the Thing handler.
     * 
     * @param channelId The channel ID without group
     * @param command The command to handle
     */
    public void handleCommand(String channelId, Command command) {
        String group = channelId.contains("#") ? channelId.split("#")[0] : "";
        String channel = channelId.contains("#") ? channelId.split("#")[1] : channelId;

        logger.trace("[{}] Handling command {} for channel {}", config.getDeviceName(), command, channelId);

        // Handle multiroom commands through the group manager
        if (GROUP_MULTIROOM.equals(group) || isMultiroomChannel(channel)) {
            groupManager.handleCommand(channel, command);
            return;
        }

        // For all other commands, send through HTTP manager
        this.httpManager.sendChannelCommand(channelId, command);
    }

    private boolean isMultiroomChannel(String channel) {
        return channel.equals(CHANNEL_JOIN) || channel.equals(CHANNEL_LEAVE) || channel.equals(CHANNEL_UNGROUP)
                || channel.equals(CHANNEL_KICKOUT) || channel.equals(CHANNEL_GROUP_VOLUME)
                || channel.equals(CHANNEL_GROUP_MUTE);
    }

    /**
     * Cleanup.
     */
    public void dispose() {
        logger.trace("[{}] Disposing device manager", config.getDeviceName());

        // Dispose all managers
        httpManager.dispose();
        uartManager.dispose();

        // Clear any cached metadata
        lastArtist = "";
        lastTitle = "";
    }

    // -------------------------------------------------------------------
    // Internal Helpers
    // -------------------------------------------------------------------

    public void updateState(String channelId, State state) {
        thingHandler.handleStateUpdate(channelId, state);
    }

    private void handleStatusUpdate(ThingStatus status) {
        thingHandler.handleStatusUpdate(status);
    }

    private void handleStatusUpdate(ThingStatus status, ThingStatusDetail detail, String msg) {
        thingHandler.handleStatusUpdate(status, detail, msg);
    }

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

    /**
     * Handle HTTP communication result
     */
    public void handleCommunicationResult(boolean success) {
        if (!success) {
            handleStatusUpdate(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Device unreachable via HTTP");
        } else {
            // Set ONLINE when we get successful communication
            if (thingHandler.getThing().getStatus() != ThingStatus.ONLINE) {
                handleStatusUpdate(ThingStatus.ONLINE);
            }
        }
    }

    /**
     * Handles parsed player status response from HTTP Manager
     */
    public void handleGetPlayerStatusResponse(JsonObject json) {
        // Update device state model
        LinkPlayDeviceState state = deviceState;

        // Log device type for future implementation
        if (json.has("type")) {
            int type = getAsInt(json, "type", 0);
            logger.warn("[{}] Device type: {} (0=Main/Standalone, 1=Multiroom Guest)", config.getDeviceName(), type);
        }

        // Playback mode
        if (json.has("mode")) {
            int mode = getAsInt(json, "mode", 0);
            String modeStr = PLAYBACK_MODES.getOrDefault(mode, "UNKNOWN");
            logger.debug("[{}] Playback mode: {} ({})", config.getDeviceName(), modeStr, mode);
            state.setSource(modeStr);
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_SOURCE, new StringType(modeStr));
        }

        // Update playback status
        if (json.has("playStatus")) {
            state.setPlayStatus(json.get("playStatus").getAsString());
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_CONTROL, new StringType(state.getPlayStatus()));
        }

        // Update metadata
        if (json.has("title")) {
            String newTitle = json.get("title").getAsString();
            state.setTrackTitle(newTitle);
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_TITLE, new StringType(newTitle));
            lastTitle = newTitle;
        }

        if (json.has("artist")) {
            String newArtist = json.get("artist").getAsString();
            state.setTrackArtist(newArtist);
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_ARTIST, new StringType(newArtist));
            lastArtist = newArtist;
        }

        // Check for album art when title or artist changes
        updateMetadata();

        if (json.has("album")) {
            state.setTrackAlbum(json.get("album").getAsString());
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_ALBUM, new StringType(state.getTrackAlbum()));
        }

        // Update volume/mute
        if (json.has("volume")) {
            state.setVolume(json.get("volume").getAsInt());
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_VOLUME, new PercentType(state.getVolume()));
        }
        if (json.has("mute")) {
            state.setMute(json.get("mute").getAsBoolean());
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_MUTE, OnOffType.from(state.isMute()));
        }

        // Update time values
        if (json.has("durationSeconds")) {
            state.setDurationSeconds(json.get("durationSeconds").getAsDouble());
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_DURATION,
                    new QuantityType<>(state.getDurationSeconds(), Units.SECOND));
        }
        if (json.has("positionSeconds")) {
            state.setPositionSeconds(json.get("positionSeconds").getAsDouble());
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_POSITION,
                    new QuantityType<>(state.getPositionSeconds(), Units.SECOND));
        }

        // Update shuffle/repeat
        if (json.has("shuffle")) {
            state.setShuffle(json.get("shuffle").getAsBoolean());
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_SHUFFLE, OnOffType.from(state.isShuffle()));
        }
        if (json.has("repeat")) {
            state.setRepeat(json.get("repeat").getAsBoolean());
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_REPEAT, OnOffType.from(state.isRepeat()));
        }
    }

    /**
     * Handles parsed device status response from HTTP Manager
     */
    public void handleGetStatusExResponse(JsonObject json) {
        // Process multiroom status first via GroupManager
        groupManager.handleDeviceStatus(json);

        // Handle rest of device status...
        if (json.has("type")) {
            int type = getAsInt(json, "type", 0);
            logger.warn("[{}] Device type: {} (0=Main/Standalone, 1=Multiroom Guest)", config.getDeviceName(), type);
        }

        // First check for UDN if we don't have one
        if (config.getUdn().isEmpty() && json.has("upnp_uuid")) {
            String discoveredUdn = getAsString(json, "upnp_uuid");
            if (!discoveredUdn.isEmpty()) {
                logger.debug("[{}] Discovered UDN via HTTP: {}", config.getDeviceName(), discoveredUdn);
                // Store in config and optionally register UPnP
                thingHandler.updateUdnInConfig(discoveredUdn);
            }
        }

        // Only update MAC if it has changed
        if (json.has("MAC")) {
            String newMac = getAsString(json, "MAC");
            if (!newMac.equals(deviceState.getDeviceMac())) {
                deviceState.setDeviceMac(newMac);
                updateState(GROUP_NETWORK + "#" + CHANNEL_MAC_ADDRESS, new StringType(newMac));
                logger.debug("[{}] Updated MAC address to: {}", config.getDeviceName(), newMac);
            }
        }

        // Only update firmware version if changed
        if (json.has("firmware")) {
            String newFirmware = getAsString(json, "firmware");
            if (!newFirmware.equals(deviceState.getFirmware())) {
                deviceState.setFirmware(newFirmware);
                updateState(GROUP_SYSTEM + "#" + CHANNEL_FIRMWARE, new StringType(newFirmware));
                logger.debug("[{}] Updated firmware version to: {}", config.getDeviceName(), newFirmware);
            }
        }

        // Only update device name if changed
        if (json.has("DeviceName")) {
            String newName = getAsString(json, "DeviceName");
            if (!newName.equals(deviceState.getDeviceName())) {
                deviceState.setDeviceName(newName);
                updateState(GROUP_SYSTEM + "#" + CHANNEL_DEVICE_NAME, new StringType(newName));
                logger.debug("[{}] Updated device name to: {}", config.getDeviceName(), newName);
            }
        }
    }

    /**
     * Get the HTTP manager instance for this device
     * 
     * @return The HTTP manager
     */
    public LinkPlayHttpManager getHttpManager() {
        return httpManager;
    }

    public LinkPlayConfiguration getConfig() {
        return config;
    }

    public LinkPlayThingHandler getThingHandler() {
        return thingHandler;
    }

    /**
     * Get the current device state
     * 
     * @return The current device state
     */
    public LinkPlayDeviceState getDeviceState() {
        return deviceState;
    }

    private void updateMetadata() {
        String artist = deviceState.getTrackArtist();
        String title = deviceState.getTrackTitle();

        // Only query if we have both non-null values
        if (artist != null && title != null) {
            metadataService.retrieveMusicMetadata(artist, title).ifPresent(url -> {
                deviceState.setAlbumArtUrl(url);
                updateState(GROUP_PLAYBACK + "#" + CHANNEL_ALBUM_ART, new StringType(url));
            });
        }
    }
}
