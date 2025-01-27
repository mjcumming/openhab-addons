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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.binding.linkplay.internal.handler.LinkPlayThingHandler;
import org.openhab.binding.linkplay.internal.metadata.MetadataService;
import org.openhab.binding.linkplay.internal.model.DeviceState;
import org.openhab.binding.linkplay.internal.multiroom.GroupManager;
import org.openhab.binding.linkplay.internal.transport.http.CommandResult;
import org.openhab.binding.linkplay.internal.transport.http.HttpManager;
import org.openhab.binding.linkplay.internal.transport.http.LinkPlayHttpClient;
import org.openhab.binding.linkplay.internal.transport.uart.UartManager;
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
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

/**
 * DeviceManager that relies on the HttpManager for
 * all polling and JSON parsing. It updates channels or Thing status
 * based on callbacks from HttpManager and UpnpManager.
 * 
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class DeviceManager {

    private static final Logger logger = LoggerFactory.getLogger(DeviceManager.class);

    private final LinkPlayThingHandler thingHandler;
    private final LinkPlayConfiguration config;
    private final HttpManager httpManager;
    private final GroupManager groupManager;
    private final MetadataService metadataService;
    private final UartManager uartManager;

    private final DeviceState deviceState;

    private static final int OFFLINE_THRESHOLD = 3;
    private int communicationFailures = 0;

    public DeviceManager(LinkPlayThingHandler thingHandler, LinkPlayConfiguration config, LinkPlayHttpClient httpClient,
            UpnpIOService upnpIOService, ThingRegistry thingRegistry, ScheduledExecutorService scheduler) {
        this.thingHandler = thingHandler;
        this.config = config;
        this.deviceState = new DeviceState();
        this.deviceState.initializeFromConfig(config); // Initialize state with config values
        this.httpManager = new HttpManager(httpClient, this, scheduler);
        this.metadataService = new MetadataService(httpClient, this);
        this.uartManager = new UartManager(this);
        this.groupManager = new GroupManager(this, thingRegistry);

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
     * Get the current device state
     * 
     * @return The current device state
     */
    public DeviceState getDeviceState() {
        return deviceState;
    }

    public GroupManager getGroupManager() {
        return groupManager;
    }

    private boolean isMultiroomChannel(String channel) {
        return channel.equals(BindingConstants.CHANNEL_JOIN) || channel.equals(BindingConstants.CHANNEL_LEAVE)
                || channel.equals(BindingConstants.CHANNEL_UNGROUP) || channel.equals(BindingConstants.CHANNEL_KICKOUT)
                || channel.equals(BindingConstants.CHANNEL_GROUP_VOLUME)
                || channel.equals(BindingConstants.CHANNEL_GROUP_MUTE);
    }

    /**
     * Handles commands from the Thing handler.
     * 
     * @param channelId The channel ID without group
     * @param command The command to handle
     */
    public void handleCommand(String channelId, Command command) {
        String[] parts = channelId.split("#", 2);
        String group = parts.length > 1 ? parts[0] : "";
        String channel = parts.length > 1 ? parts[1] : channelId;

        logger.trace("[{}] Handling command {} for channel {}", config.getDeviceName(), command, channelId);

        // Handle REFRESH command first
        if (command instanceof RefreshType) {
            handleRefreshCommand(channel);
            return;
        }

        // Handle multiroom commands through the group manager
        if (BindingConstants.GROUP_MULTIROOM.equals(group) || isMultiroomChannel(channel)) {
            groupManager.handleCommand(channel, command);
            return;
        }

        // For all other commands, handle through HTTP manager with state updates
        CompletableFuture<CommandResult> future = null;

        try {
            switch (channel) {
                case BindingConstants.CHANNEL_VOLUME:
                    try {
                        int volume = Integer.parseInt(command.toString());
                        future = httpManager.setVolume(volume).thenApply(result -> {
                            if (result.isSuccess()) {
                                deviceState.setVolume(volume);
                                updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_VOLUME,
                                        new PercentType(volume));
                            }
                            return result;
                        });
                    } catch (NumberFormatException e) {
                        logger.warn("[{}] Invalid volume value: {}", config.getDeviceName(), command);
                    }
                    break;

                case BindingConstants.CHANNEL_MUTE:
                    boolean mute = command.toString().equalsIgnoreCase("ON");
                    future = httpManager.setMute(mute).thenApply(result -> {
                        if (result.isSuccess()) {
                            deviceState.setMute(mute);
                            updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_MUTE,
                                    OnOffType.from(mute));
                        }
                        return result;
                    });
                    break;

                case BindingConstants.CHANNEL_CONTROL:
                    switch (command.toString().toUpperCase()) {
                        case "PLAY":
                            future = httpManager.resume().thenApply(result -> {
                                if (result.isSuccess()) {
                                    deviceState.setControl(BindingConstants.CONTROL_PLAY);
                                    updateState(
                                            BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_CONTROL,
                                            new StringType(BindingConstants.CONTROL_PLAY));
                                }
                                return result;
                            });
                            break;
                        case "PAUSE":
                            future = httpManager.pause().thenApply(result -> {
                                if (result.isSuccess()) {
                                    deviceState.setControl(BindingConstants.CONTROL_PAUSE);
                                    updateState(
                                            BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_CONTROL,
                                            new StringType(BindingConstants.CONTROL_PAUSE));
                                }
                                return result;
                            });
                            break;
                        case "NEXT":
                            future = httpManager.next().thenApply(result -> {
                                if (result.isSuccess()) {
                                    deviceState.setControl(BindingConstants.CONTROL_PLAY);
                                    updateState(
                                            BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_CONTROL,
                                            new StringType(BindingConstants.CONTROL_PLAY));
                                }
                                return result;
                            });
                            break;
                        case "PREVIOUS":
                            future = httpManager.previous().thenApply(result -> {
                                if (result.isSuccess()) {
                                    deviceState.setControl(BindingConstants.CONTROL_PLAY);
                                    updateState(
                                            BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_CONTROL,
                                            new StringType(BindingConstants.CONTROL_PLAY));
                                }
                                return result;
                            });
                            break;
                    }
                    break;

                case BindingConstants.CHANNEL_REPEAT:
                case BindingConstants.CHANNEL_SHUFFLE:
                    boolean newState = command.toString().equalsIgnoreCase("ON");
                    boolean repeat = deviceState.isRepeat();
                    boolean shuffle = deviceState.isShuffle();

                    if (channel.equals(BindingConstants.CHANNEL_REPEAT)) {
                        repeat = newState;
                    } else {
                        shuffle = newState;
                    }

                    final boolean finalRepeat = repeat;
                    final boolean finalShuffle = shuffle;
                    future = httpManager.setPlayMode(repeat, shuffle).thenApply(result -> {
                        if (result.isSuccess()) {
                            deviceState.setRepeat(finalRepeat);
                            deviceState.setShuffle(finalShuffle);
                            updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_REPEAT,
                                    OnOffType.from(finalRepeat));
                            updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_SHUFFLE,
                                    OnOffType.from(finalShuffle));
                        }
                        return result;
                    });
                    break;

                case BindingConstants.CHANNEL_INPUT_SOURCE:
                    if (command instanceof StringType) {
                        String requestedSource = command.toString();
                        String sourceCommand = BindingConstants.INPUT_SOURCE_COMMANDS.get(requestedSource);
                        if (sourceCommand != null) {
                            future = httpManager.setPlayMode(sourceCommand).thenApply(result -> {
                                if (result.isSuccess()) {
                                    deviceState.setInputSource(requestedSource);
                                    updateState(
                                            BindingConstants.GROUP_PLAYBACK + "#"
                                                    + BindingConstants.CHANNEL_INPUT_SOURCE,
                                            new StringType(requestedSource));
                                }
                                return result;
                            });
                        } else {
                            logger.warn("[{}] Unsupported input source requested: {}", config.getDeviceName(),
                                    requestedSource);
                        }
                    }
                    break;

                default:
                    logger.warn("[{}] Unhandled channel {} command {}", config.getDeviceName(), channelId, command);
                    break;
            }

            if (future != null) {
                future.exceptionally(e -> {
                    logger.warn("[{}] Error processing command {} for channel {}: {}", config.getDeviceName(), command,
                            channelId, e.getMessage());
                    return CommandResult.error("Command execution failed: " + e.getMessage());
                });
            }
        } catch (Exception e) {
            logger.warn("[{}] Error sending command {} to channel {}: {}", config.getDeviceName(), command, channelId,
                    e.getMessage());
            // Create an error result for the exception case too
            future = CompletableFuture.completedFuture(CommandResult.error(e));
        }
    }

    /**
     * Handle REFRESH commands for all channels
     */
    private void handleRefreshCommand(String channel) {
        switch (channel) {
            // Playback channels
            case BindingConstants.CHANNEL_TITLE:
            case BindingConstants.CHANNEL_ARTIST:
            case BindingConstants.CHANNEL_ALBUM:
            case BindingConstants.CHANNEL_ALBUM_ART:
            case BindingConstants.CHANNEL_DURATION:
            case BindingConstants.CHANNEL_POSITION:
            case BindingConstants.CHANNEL_VOLUME:
            case BindingConstants.CHANNEL_MUTE:
            case BindingConstants.CHANNEL_CONTROL:
            case BindingConstants.CHANNEL_MODE:
                httpManager.getPlayerStatus();
                break;

            // System channels
            case BindingConstants.CHANNEL_DEVICE_NAME:
            case BindingConstants.CHANNEL_FIRMWARE:
            case BindingConstants.CHANNEL_MAC_ADDRESS:
            case BindingConstants.CHANNEL_WIFI_SIGNAL:
                httpManager.getStatusEx();
                break;

            // Multiroom channels handled by GroupManager
            case BindingConstants.CHANNEL_ROLE:
            case BindingConstants.CHANNEL_MASTER_IP:
            case BindingConstants.CHANNEL_SLAVE_IPS:
            case BindingConstants.CHANNEL_GROUP_NAME:
                groupManager.updateMultiroomStatus();
                break;

            default:
                logger.debug("[{}] Unhandled REFRESH command for channel: {}", config.getDeviceName(), channel);
                break;
        }
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
     * 
     * @param success Whether the communication was successful
     * @param source Optional source of the communication result (e.g. "player status", "device status")
     */
    public void handleCommunicationResult(boolean success, String source) {
        if (!success) {
            logger.debug("[{}] Communication failed: {}", config.getDeviceName(), source);
            communicationFailures++;

            // Only go offline after threshold failures and if currently online
            if (communicationFailures >= OFFLINE_THRESHOLD
                    && thingHandler.getThing().getStatus() == ThingStatus.ONLINE) {
                handleStatusUpdate(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Device unreachable: " + source);
            }
        } else {
            // Reset counter on success and ensure online
            communicationFailures = 0;
            if (thingHandler.getThing().getStatus() != ThingStatus.ONLINE) {
                logger.info("[{}] Communication restored: {}", config.getDeviceName(), source);
                handleStatusUpdate(ThingStatus.ONLINE);
            }
        }
    }

    /**
     * Process player status response from the API
     */
    public void handleGetPlayerStatusResponse(JsonObject json) {
        try {
            int modeInt = Integer.parseInt(getJsonString(json, "mode"));

            // Map the numeric mode to a string constant
            String mode = switch (modeInt) {
                case -1 -> BindingConstants.MODE_IDLE;
                case 1 -> BindingConstants.MODE_AIRPLAY;
                case 2 -> BindingConstants.MODE_DLNA;
                case 10 -> BindingConstants.MODE_NETWORK;
                case 21 -> BindingConstants.MODE_UDISK;
                case 31 -> BindingConstants.MODE_SPOTIFY;
                case 32 -> BindingConstants.MODE_TIDAL;
                case 40 -> BindingConstants.MODE_LINE_IN;
                case 41 -> BindingConstants.MODE_BLUETOOTH;
                case 43 -> BindingConstants.MODE_OPTICAL;
                case 45 -> BindingConstants.MODE_COAXIAL;
                case 47 -> BindingConstants.MODE_LINE_IN_2;
                case 51 -> BindingConstants.MODE_USB_DAC;
                case 56 -> BindingConstants.MODE_OPTICAL_2;
                case 57 -> BindingConstants.MODE_COAXIAL_2;
                default -> BindingConstants.MODE_UNKNOWN;
            };

            // First update the read-only mode channel using PLAYBACK_MODES map
            deviceState.setMode(mode);
            updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_MODE, new StringType(mode));

            // Then map the mode to an input source if applicable
            String inputSource = switch (mode) {
                case "LINE_IN" -> BindingConstants.INPUT_LINE_IN;
                case "LINE_IN_2" -> BindingConstants.INPUT_LINE_IN_2;
                case "BLUETOOTH" -> BindingConstants.INPUT_BLUETOOTH;
                case "OPTICAL" -> BindingConstants.INPUT_OPTICAL;
                case "OPTICAL_2" -> BindingConstants.INPUT_OPTICAL_2;
                case "COAXIAL" -> BindingConstants.INPUT_COAXIAL;
                case "COAXIAL_2" -> BindingConstants.INPUT_COAXIAL_2;
                case "USB_DAC" -> BindingConstants.INPUT_USB_DAC;
                case "UDISK" -> BindingConstants.INPUT_UDISK;
                case "NETWORK", "AIRPLAY", "DLNA", "SPOTIFY", "TIDAL" -> BindingConstants.INPUT_WIFI;
                default -> BindingConstants.INPUT_UNKNOWN;
            };

            deviceState.setInputSource(inputSource);
            updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_INPUT_SOURCE,
                    new StringType(inputSource));

            logger.debug("[{}] Status updated: mode={}, input={} (raw={})", config.getDeviceName(), mode, inputSource,
                    modeInt);
        } catch (NumberFormatException e) {
            logger.debug("[{}] Invalid mode value in JSON", config.getDeviceName());
            deviceState.setMode(BindingConstants.MODE_UNKNOWN);
            deviceState.setInputSource(BindingConstants.INPUT_UNKNOWN);
            updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_MODE,
                    new StringType(BindingConstants.MODE_UNKNOWN));
            updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_INPUT_SOURCE,
                    new StringType(BindingConstants.INPUT_UNKNOWN));
        }

        // Process status/control using control constants
        String status = getJsonString(json, "status").toLowerCase();
        String control = switch (status) {
            case "play" -> BindingConstants.CONTROL_PLAY;
            case "stop" -> BindingConstants.CONTROL_STOP;
            case "load" -> BindingConstants.CONTROL_LOAD;
            case "pause", "none" -> BindingConstants.CONTROL_PAUSE;
            default -> BindingConstants.CONTROL_PAUSE;
        };
        deviceState.setControl(control);
        updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_CONTROL,
                control.equals(BindingConstants.CONTROL_PLAY) ? PlayPauseType.PLAY : PlayPauseType.PAUSE);

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

        updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_TITLE, new StringType(newTitle));
        updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_ARTIST, new StringType(newArtist));
        updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_ALBUM, new StringType(newAlbum));

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

            updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_POSITION,
                    new QuantityType<>(position, Units.SECOND));
            updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_DURATION,
                    new QuantityType<>(duration, Units.SECOND));
        } catch (NumberFormatException e) {
            logger.debug("[{}] Failed to parse position/duration: {}", config.getDeviceName(), e.getMessage());
        }

        // Process volume and mute
        try {
            int volume = Integer.parseInt(getJsonString(json, "vol"));
            boolean mute = "1".equals(getJsonString(json, "mute"));

            deviceState.setVolume(volume);
            deviceState.setMute(mute);

            updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_VOLUME,
                    new PercentType(volume));
            updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_MUTE, OnOffType.from(mute));
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

            updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_REPEAT,
                    OnOffType.from(repeat));
            updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_SHUFFLE,
                    OnOffType.from(shuffle));
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

        // Handle device name from API - just track it, don't use for identification
        String apiDeviceName = getJsonString(json, "DeviceName");
        if (!apiDeviceName.isEmpty() && !apiDeviceName.equals(deviceState.getDeviceName())) {
            deviceState.setDeviceName(apiDeviceName);
            updateState(BindingConstants.GROUP_SYSTEM + "#" + BindingConstants.CHANNEL_DEVICE_NAME,
                    new StringType(apiDeviceName));
            logger.debug("[{}] Updated API device name to: {}", config.getDeviceName(), apiDeviceName);
        }

        // First check for UDN if we don't have one
        if (config.getUdn().isEmpty()) {
            String discoveredUdn = getJsonString(json, "upnp_uuid");
            if (!discoveredUdn.isEmpty()) {
                logger.debug("[{}] Discovered UDN via HTTP: {}", config.getDeviceName(), discoveredUdn);
                // Store in config and optionally register UPnP
                thingHandler.updateUdnInConfig(discoveredUdn);
            }
        }

        // Only update MAC if it has changed
        String newMac = getJsonString(json, "MAC");
        if (!newMac.isEmpty() && !newMac.equals(deviceState.getDeviceMac())) {
            deviceState.setDeviceMac(newMac);
            updateState(BindingConstants.GROUP_NETWORK + "#" + BindingConstants.CHANNEL_MAC_ADDRESS,
                    new StringType(newMac));
            logger.debug("[{}] Updated MAC address to: {}", config.getDeviceName(), newMac);
        }

        // Only update firmware version if changed
        String newFirmware = getJsonString(json, "firmware");
        if (!newFirmware.isEmpty() && !newFirmware.equals(deviceState.getFirmware())) {
            deviceState.setFirmware(newFirmware);
            updateState(BindingConstants.GROUP_SYSTEM + "#" + BindingConstants.CHANNEL_FIRMWARE,
                    new StringType(newFirmware));
            logger.debug("[{}] Updated firmware version to: {}", config.getDeviceName(), newFirmware);
        }

        // Handle WiFi signal strength (RSSI)
        int rssi = getJsonInt(json, "RSSI", -100);
        // Convert RSSI to percentage (typical range: -100 dBm to -50 dBm)
        int signalStrength;
        if (rssi <= -100) {
            signalStrength = 0;
        } else if (rssi >= -50) {
            signalStrength = 100;
        } else {
            signalStrength = 2 * (rssi + 100); // Linear conversion from -100..-50 to 0..100
        }
        // Only update if signal strength has changed
        if (signalStrength != deviceState.getWifiSignalStrength()) {
            deviceState.setWifiSignalDbm(rssi);
            updateState(BindingConstants.GROUP_NETWORK + "#" + BindingConstants.CHANNEL_WIFI_SIGNAL,
                    new PercentType(signalStrength));
            logger.debug("[{}] Updated WiFi signal strength: {}% (RSSI: {} dBm)", config.getDeviceName(),
                    signalStrength, rssi);
        }
    }

    /**
     * Get the HTTP manager instance for this device
     * 
     * @return The HTTP manager
     */
    public HttpManager getHttpManager() {
        return httpManager;
    }

    public LinkPlayConfiguration getConfig() {
        return config;
    }

    public LinkPlayThingHandler getThingHandler() {
        return thingHandler;
    }

    private void updateMetadata() {
        String artist = deviceState.getTrackArtist();
        String title = deviceState.getTrackTitle();

        // Only query if we have both non-null values
        if (artist != null && title != null) {
            metadataService.retrieveMusicMetadata(artist, title).ifPresent(url -> {
                deviceState.setAlbumArtUrl(url);
                updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_ALBUM_ART,
                        new StringType(url));
            });
        }
    }
}
