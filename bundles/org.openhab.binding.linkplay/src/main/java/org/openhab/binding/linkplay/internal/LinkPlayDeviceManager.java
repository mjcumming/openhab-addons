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
import org.openhab.binding.linkplay.internal.utils.HexConverter;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.PlayPauseType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ThingRegistry;
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
    private final LinkPlayHttpManager httpManager;
    private final LinkPlayGroupManager groupManager;
    private final LinkPlayMetadataService metadataService;
    private final LinkPlayUartManager uartManager;

    private final LinkPlayDeviceState deviceState;

    // Define playback mode mapping
    private static final Map<Integer, String> PLAYBACK_MODES = Map.ofEntries(Map.entry(-1, "IDLE"),
            Map.entry(0, "IDLE"), Map.entry(1, "BLUETOOTH"), Map.entry(2, "LINE-IN"), Map.entry(3, "OPTICAL"),
            Map.entry(4, "COAXIAL"), Map.entry(10, "WIFI"), Map.entry(11, "SPOTIFY"), Map.entry(12, "AIRPLAY"),
            Map.entry(13, "DLNA"), Map.entry(14, "MULTIROOM"), Map.entry(15, "USB"), Map.entry(16, "TF_CARD"),
            Map.entry(17, "TIDAL"), Map.entry(18, "AMAZON"), Map.entry(19, "QPLAY"), Map.entry(20, "QOBUZ"),
            Map.entry(21, "DEEZER"), Map.entry(22, "NAPSTER"), Map.entry(23, "TUNEIN"), Map.entry(24, "IHEARTRADIO"),
            Map.entry(25, "CUSTOM"));

    public LinkPlayDeviceManager(LinkPlayThingHandler thingHandler, LinkPlayConfiguration config,
            LinkPlayHttpClient httpClient, UpnpIOService upnpIOService, ThingRegistry thingRegistry) {
        this.thingHandler = thingHandler;
        this.config = config;

        // Initialize device state from config
        deviceState = new LinkPlayDeviceState();
        deviceState.initializeFromConfig(config);

        // Create managers with simplified dependencies
        this.httpManager = new LinkPlayHttpManager(httpClient, this);
        this.uartManager = new LinkPlayUartManager(this);
        this.metadataService = new LinkPlayMetadataService(httpClient, this);
        this.groupManager = new LinkPlayGroupManager(this, thingRegistry);

        logger.debug("[{}] Initializing device manager...", config.getDeviceName());
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

    private String getJsonString(JsonObject obj, String key) {
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

    private int getJsonInt(JsonObject obj, String key, int defaultValue) {
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
     * Process player status response from the API
     */
    public void handleGetPlayerStatusResponse(JsonObject json) {
        // Process mode/source using our new PLAYBACK_MODES map
        try {
            int modeInt = Integer.parseInt(getJsonString(json, "mode"));
            String source = PLAYBACK_MODES.getOrDefault(modeInt, SOURCE_UNKNOWN);
            deviceState.setSource(source);
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_SOURCE, new StringType(source));
        } catch (NumberFormatException e) {
            logger.debug("[{}] Invalid mode value in JSON", config.getDeviceName());
            deviceState.setSource(SOURCE_UNKNOWN);
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_SOURCE, new StringType(SOURCE_UNKNOWN));
        }

        // Process status/control using our new control constants
        String status = getJsonString(json, "status").toLowerCase();
        String control = switch (status) {
            case "play" -> CONTROL_PLAY;
            case "stop" -> CONTROL_STOP;
            case "load" -> CONTROL_LOAD;
            case "pause", "none" -> CONTROL_PAUSE;
            default -> CONTROL_PAUSE;
        };
        deviceState.setControl(control);
        updateState(GROUP_PLAYBACK + "#" + CHANNEL_CONTROL,
                control.equals(CONTROL_PLAY) ? PlayPauseType.PLAY : PlayPauseType.PAUSE);

        // Process metadata with hex decoding
        String newTitle = HexConverter.hexToString(getJsonString(json, "Title"));
        String newArtist = HexConverter.hexToString(getJsonString(json, "Artist"));
        String newAlbum = HexConverter.hexToString(getJsonString(json, "Album"));

        boolean titleChanged = !newTitle.equals(deviceState.getTrackTitle());
        boolean artistChanged = !newArtist.equals(deviceState.getTrackArtist());

        // Update state and channels
        deviceState.setTrackTitle(newTitle);
        deviceState.setTrackArtist(newArtist);
        deviceState.setTrackAlbum(newAlbum);

        updateState(GROUP_PLAYBACK + "#" + CHANNEL_TITLE, new StringType(newTitle));
        updateState(GROUP_PLAYBACK + "#" + CHANNEL_ARTIST, new StringType(newArtist));
        updateState(GROUP_PLAYBACK + "#" + CHANNEL_ALBUM, new StringType(newAlbum));

        // Only update metadata if both title and artist changed and are non-empty
        if (titleChanged && artistChanged && !newTitle.isEmpty() && !newArtist.isEmpty()) {
            updateMetadata();
        }

        // Process position and duration
        try {
            long curpos = Long.parseLong(getJsonString(json, "curpos"));
            long totlen = Long.parseLong(getJsonString(json, "totlen"));

            // Convert milliseconds to seconds
            int position = (int) (curpos / 1000);
            int duration = (int) (totlen / 1000);

            logger.trace("[{}] Parsed position: {}s, duration: {}s", config.getDeviceName(), position, duration);

            updateState(GROUP_PLAYBACK + "#" + CHANNEL_POSITION, new QuantityType<>(position, Units.SECOND));
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_DURATION, new QuantityType<>(duration, Units.SECOND));
        } catch (NumberFormatException e) {
            logger.debug("[{}] Failed to parse position/duration: {}", config.getDeviceName(), e.getMessage());
        }

        // Process volume and mute
        try {
            int volume = Integer.parseInt(getJsonString(json, "vol"));
            boolean mute = "1".equals(getJsonString(json, "mute"));

            deviceState.setVolume(volume);
            deviceState.setMute(mute);

            updateState(GROUP_PLAYBACK + "#" + CHANNEL_VOLUME, new PercentType(volume));
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_MUTE, OnOffType.from(mute));
        } catch (NumberFormatException e) {
            logger.debug("[{}] Failed to parse volume/mute: {}", config.getDeviceName(), e.getMessage());
        }

        // Process repeat and shuffle based on loop mode
        try {
            int loopMode = Integer.parseInt(getJsonString(json, "loop"));
            boolean repeat = (loopMode & 1) == 1; // Bit 0: repeat
            boolean shuffle = (loopMode & 2) == 2; // Bit 1: shuffle

            deviceState.setRepeat(repeat);
            deviceState.setShuffle(shuffle);

            updateState(GROUP_PLAYBACK + "#" + CHANNEL_REPEAT, OnOffType.from(repeat));
            updateState(GROUP_PLAYBACK + "#" + CHANNEL_SHUFFLE, OnOffType.from(shuffle));
        } catch (NumberFormatException e) {
            logger.debug("[{}] Failed to parse loop mode: {}", config.getDeviceName(), e.getMessage());
        }
    }

    /**
     * Handles parsed device status response from HTTP Manager
     */
    public void handleGetStatusExResponse(JsonObject json) {
        // Process multiroom status first via GroupManager
        groupManager.handleDeviceStatus(json);

        // Handle device name - only process if changed or missing
        if (json.has("DeviceName")) {
            String newName = getJsonString(json, "DeviceName");
            String currentName = deviceState.getDeviceName();

            // Only update if name changed or current name is empty
            if (!newName.equals(currentName) || currentName.isEmpty()) {
                if (!newName.isEmpty()) {
                    // Use name from API if available
                    deviceState.setDeviceName(newName);
                    updateState(GROUP_SYSTEM + "#" + CHANNEL_DEVICE_NAME, new StringType(newName));
                    logger.debug("[{}] Updated device name to: {}", config.getDeviceName(), newName);
                } else {
                    // If empty from API, use configured name
                    String configName = config.getDeviceName();
                    if (!configName.isEmpty()) {
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
        }

        // First check for UDN if we don't have one
        if (config.getUdn().isEmpty() && json.has("upnp_uuid")) {
            String discoveredUdn = getJsonString(json, "upnp_uuid");
            if (!discoveredUdn.isEmpty()) {
                logger.debug("[{}] Discovered UDN via HTTP: {}", config.getDeviceName(), discoveredUdn);
                // Store in config and optionally register UPnP
                thingHandler.updateUdnInConfig(discoveredUdn);
            }
        }

        // Only update MAC if it has changed
        if (json.has("MAC")) {
            String newMac = getJsonString(json, "MAC");
            if (!newMac.equals(deviceState.getDeviceMac())) {
                deviceState.setDeviceMac(newMac);
                updateState(GROUP_NETWORK + "#" + CHANNEL_MAC_ADDRESS, new StringType(newMac));
                logger.debug("[{}] Updated MAC address to: {}", config.getDeviceName(), newMac);
            }
        }

        // Only update firmware version if changed
        if (json.has("firmware")) {
            String newFirmware = getJsonString(json, "firmware");
            if (!newFirmware.equals(deviceState.getFirmware())) {
                deviceState.setFirmware(newFirmware);
                updateState(GROUP_SYSTEM + "#" + CHANNEL_FIRMWARE, new StringType(newFirmware));
                logger.debug("[{}] Updated firmware version to: {}", config.getDeviceName(), newFirmware);
            }
        }

        // Handle WiFi signal strength (RSSI)
        if (json.has("RSSI")) {
            int rssi = getJsonInt(json, "RSSI", 0);
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

    public LinkPlayGroupManager getGroupManager() {
        return groupManager;
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
