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

import java.util.Map;

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

    // Define playback mode mapping
    private static final Map<Integer, String> PLAYBACK_MODES = Map.ofEntries(Map.entry(-1, "IDLE"),
            Map.entry(0, "IDLE"), Map.entry(1, "BLUETOOTH"), Map.entry(2, "LINE-IN"), Map.entry(3, "OPTICAL"),
            Map.entry(4, "COAXIAL"), Map.entry(10, "WIFI"), Map.entry(11, "SPOTIFY"), Map.entry(12, "AIRPLAY"),
            Map.entry(13, "DLNA"), Map.entry(14, "MULTIROOM"), Map.entry(15, "USB"), Map.entry(16, "TF_CARD"),
            Map.entry(17, "TIDAL"), Map.entry(18, "AMAZON"), Map.entry(19, "QPLAY"), Map.entry(20, "QOBUZ"),
            Map.entry(21, "DEEZER"), Map.entry(22, "NAPSTER"), Map.entry(23, "TUNEIN"), Map.entry(24, "IHEARTRADIO"),
            Map.entry(25, "CUSTOM"));

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
        boolean metadataChanged = false;

        // Playback mode/source
        String mode = getAsString(json, "mode");
        if (!mode.isEmpty()) {
            int modeInt = Integer.parseInt(mode);
            String source = PLAYBACK_MODES.getOrDefault(modeInt, "UNKNOWN");
            state.setSource(source);
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_SOURCE, new StringType(source));
            logger.debug("[{}] Updated source to: {} (mode={})", config.getDeviceName(), source, mode);
        } else {
            // Default to IDLE if no mode
            state.setSource("IDLE");
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_SOURCE, new StringType("IDLE"));
            logger.debug("[{}] No mode field, defaulting source to IDLE", config.getDeviceName());
        }

        // Update playback status
        String status = getAsString(json, "status");
        if (!status.isEmpty()) {
            status = status.toLowerCase();
            State newState;

            // Convert status to proper PlayPauseType
            switch (status) {
                case "play":
                case "playing":
                    newState = PlayPauseType.PLAY;
                    break;
                case "pause":
                case "paused":
                case "none": // Handle "none" status explicitly
                case "stop":
                case "stopped":
                default:
                    newState = PlayPauseType.PAUSE;
                    break;
            }

            state.setPlayStatus(status);
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_CONTROL, newState);
            logger.debug("[{}] Updated playback control to: {} (raw: {})", config.getDeviceName(), newState, status);
        } else {
            // Only log when truly empty/missing
            state.setPlayStatus("stop");
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_CONTROL, PlayPauseType.PAUSE);
            logger.debug("[{}] Empty status field in JSON, defaulting control to PAUSE", config.getDeviceName());
        }

        // Update metadata with proper change detection
        if (json.has("Title")) {
            String newTitle = getAsString(json, "Title");
            String currentTitle = state.getTrackTitle();
            if (currentTitle == null || !newTitle.equals(currentTitle)) {
                state.setTrackTitle(newTitle);
                updateState(GROUP_PLAYBACK + "#" + CHANNEL_TITLE, new StringType(newTitle));
                metadataChanged = true;
                lastTitle = newTitle;
            }
        }

        if (json.has("Artist")) {
            String newArtist = getAsString(json, "Artist");
            String currentArtist = state.getTrackArtist();
            if (currentArtist == null || !newArtist.equals(currentArtist)) {
                state.setTrackArtist(newArtist);
                updateState(GROUP_PLAYBACK + "#" + CHANNEL_ARTIST, new StringType(newArtist));
                metadataChanged = true;
                lastArtist = newArtist;
            }
        }

        // Only update metadata if artist or title actually changed
        if (metadataChanged) {
            logger.debug("[{}] Track changed: artist='{}' title='{}', updating metadata", config.getDeviceName(),
                    state.getTrackArtist(), state.getTrackTitle());
            updateMetadata();
        }

        if (json.has("album")) {
            state.setTrackAlbum(json.get("album").getAsString());
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_ALBUM, new StringType(state.getTrackAlbum()));
        }

        // Update volume/mute with proper type handling
        if (json.has("vol")) {
            int volume = getAsInt(json, "vol", 0);
            state.setVolume(volume);
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_VOLUME, new PercentType(volume));

            // update group volume, if needed
            groupManager.handleDeviceVolumeChange(config.getIpAddress(), volume);
        }
        if (json.has("mute")) {
            // Handle mute as integer (0/1) or string ("0"/"1")
            String muteStr = getAsString(json, "mute");
            boolean mute = "1".equals(muteStr) || "true".equalsIgnoreCase(muteStr);
            state.setMute(mute);
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_MUTE, OnOffType.from(mute));
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

        // Update shuffle/repeat based on loop mode
        // Loop modes:
        // 0 = Repeat All
        // 1 = Repeat One
        // 2 = Shuffle All + Repeat
        // 3 = Shuffle All No Repeat
        // 4 = No Shuffle No Repeat (default)
        // 5 = Shuffle All + Repeat One
        if (json.has("loop")) {
            int loopMode = getAsInt(json, "loop", 4);

            // Shuffle ON for modes 2,3,5
            boolean shuffle = (loopMode == 2 || loopMode == 3 || loopMode == 5);
            state.setShuffle(shuffle);
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_SHUFFLE, OnOffType.from(shuffle));

            // Repeat ON for modes 0,1,2,5 (any mode with repeat all or repeat one)
            boolean repeat = (loopMode == 0 || loopMode == 1 || loopMode == 2 || loopMode == 5);
            state.setRepeat(repeat);
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_REPEAT, OnOffType.from(repeat));

            logger.debug(
                    "[{}] Loop mode {} -> shuffle={}, repeat={} (repeat modes: 0=all, 1=one, 2=shuffle+repeat, 3=shuffle, 4=none, 5=shuffle+repeat_one)",
                    config.getDeviceName(), loopMode, shuffle, repeat);
        } else {
            // Always ensure we have a valid state, default to OFF if no loop mode
            state.setShuffle(false);
            state.setRepeat(false);
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_SHUFFLE, OnOffType.OFF);
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_REPEAT, OnOffType.OFF);
            logger.debug("[{}] No loop field in JSON, defaulting shuffle and repeat to OFF", config.getDeviceName());
        }
    }

    /**
     * Handles parsed device status response from HTTP Manager
     */
    public void handleGetStatusExResponse(JsonObject json) {
        // Process multiroom status first via GroupManager
        groupManager.handleDeviceStatus(json);

        // Handle device name - ensure we always have a valid state
        if (json.has("DeviceName")) {
            String newName = getAsString(json, "DeviceName");
            if (!newName.isEmpty()) {
                deviceState.setDeviceName(newName);
                updateState(GROUP_SYSTEM + "#" + CHANNEL_DEVICE_NAME, new StringType(newName));
                logger.debug("[{}] Updated device name to: {}", config.getDeviceName(), newName);
            } else {
                // If empty from API, use configured name
                String configName = config.getDeviceName();
                if (configName != null && !configName.isEmpty()) {
                    deviceState.setDeviceName(configName);
                    updateState(GROUP_SYSTEM + "#" + CHANNEL_DEVICE_NAME, new StringType(configName));
                    logger.debug("[{}] Using configured device name: {}", config.getDeviceName(), configName);
                } else {
                    // Last resort - use thing label or ID
                    String fallbackName = thingHandler.getThing().getLabel();
                    if (fallbackName == null || fallbackName.isEmpty()) {
                        fallbackName = thingHandler.getThing().getUID().getId();
                    }
                    deviceState.setDeviceName(fallbackName);
                    updateState(GROUP_SYSTEM + "#" + CHANNEL_DEVICE_NAME, new StringType(fallbackName));
                    logger.debug("[{}] Using fallback device name: {}", config.getDeviceName(), fallbackName);
                }
            }
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

        // Handle WiFi signal strength (RSSI)
        if (json.has("RSSI")) {
            int rssi = getAsInt(json, "RSSI", 0);
            // Convert RSSI to percentage (typical range: -100 dBm to -50 dBm)
            int signalStrength;
            if (rssi <= -100) {
                signalStrength = 0;
            } else if (rssi >= -50) {
                signalStrength = 100;
            } else {
                signalStrength = 2 * (rssi + 100); // Linear conversion from -100..-50 to 0..100
            }
            // Update channel only since we don't store in device state
            updateState(GROUP_NETWORK + "#" + CHANNEL_WIFI_SIGNAL, new PercentType(signalStrength));
            logger.debug("[{}] Updated WiFi signal strength: {}% (RSSI: {} dBm)", config.getDeviceName(),
                    signalStrength, rssi);
        } else {
            // Ensure we always have a valid state
            updateState(GROUP_NETWORK + "#" + CHANNEL_WIFI_SIGNAL, new PercentType(0));
            logger.debug("[{}] No RSSI data, setting WiFi signal strength to 0%", config.getDeviceName());
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
