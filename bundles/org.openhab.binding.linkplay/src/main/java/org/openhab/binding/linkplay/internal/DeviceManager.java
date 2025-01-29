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
import org.openhab.core.library.types.DecimalType;
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
 * The {@link DeviceManager} is the central coordinator for a LinkPlay audio device.
 * It manages the device's lifecycle and state, coordinating between different subsystems:
 * <ul>
 * <li>HTTP Manager - Primary communication for device control and status polling</li>
 * <li>Group Manager - Handles multiroom audio grouping functionality</li>
 * <li>UART Manager - Optional serial communication support</li>
 * <li>Metadata Service - Enriches audio playback with additional metadata</li>
 * </ul>
 * 
 * Key responsibilities:
 * <ul>
 * <li>Maintains device state and synchronizes with OpenHAB channels</li>
 * <li>Routes commands to appropriate subsystems</li>
 * <li>Monitors device connectivity and health</li>
 * <li>Coordinates metadata enrichment for audio content</li>
 * </ul>
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

    /**
     * Creates a new DeviceManager instance for a LinkPlay device.
     * Initializes all required subsystems but does not start communication.
     *
     * @param thingHandler The LinkPlay thing handler this manager is associated with
     * @param config The binding configuration for this device
     * @param httpClient The HTTP client for device communication
     * @param upnpIOService The UPnP I/O service for device discovery
     * @param thingRegistry The OpenHAB thing registry for multiroom coordination
     * @param scheduler The scheduler for periodic tasks
     */
    public DeviceManager(LinkPlayThingHandler thingHandler, LinkPlayConfiguration config, LinkPlayHttpClient httpClient,
            UpnpIOService upnpIOService, ThingRegistry thingRegistry, ScheduledExecutorService scheduler) {
        this.thingHandler = thingHandler;
        this.config = config;
        this.deviceState = new DeviceState();
        this.deviceState.initializeFromConfig(config); // Initialize state with config values
        this.httpManager = new HttpManager(httpClient, this, scheduler);
        this.metadataService = new MetadataService(httpClient, this);
        this.uartManager = new UartManager(this);
        this.groupManager = new GroupManager(this, httpManager, thingRegistry);

        logger.debug("[{}] Initializing device manager...", config.getDeviceName());
    }

    /**
     * Initializes the device manager and starts device communication.
     * Sets initial Thing status to UNKNOWN while attempting to establish connection.
     * Starts the HTTP polling mechanism as the primary communication method.
     * Additional features like UPnP and metadata services are initialized after successful connection.
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
     * Handles commands received from the Thing handler.
     * Routes commands to appropriate subsystems based on channel group and type:
     * <ul>
     * <li>Playback controls (play, pause, next, previous, volume, mute)</li>
     * <li>Multiroom controls (join, leave, group management)</li>
     * <li>Input source selection</li>
     * <li>Playback mode settings (repeat, shuffle)</li>
     * </ul>
     *
     * @param channelId The channel ID (may include group prefix)
     * @param command The command to execute
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
                case BindingConstants.CHANNEL_LOOP_ONCE:
                    boolean newState = command.toString().equalsIgnoreCase("ON");
                    boolean repeat = deviceState.isRepeat();
                    boolean shuffle = deviceState.isShuffle();
                    boolean loopOnce = deviceState.isLoopOnce();

                    // Update the appropriate flag based on which channel received the command
                    if (channel.equals(BindingConstants.CHANNEL_REPEAT)) {
                        repeat = newState;
                    } else if (channel.equals(BindingConstants.CHANNEL_LOOP_ONCE)) {
                        loopOnce = newState;
                    } else {
                        shuffle = newState;
                    }

                    // Calculate loop mode value based on combination:
                    int loopMode;
                    if (loopOnce) {
                        loopMode = shuffle ? 5 : 1; // Loop once with/without shuffle
                    } else if (repeat && shuffle) {
                        loopMode = 2; // Loop with shuffle
                    } else if (repeat) {
                        loopMode = 0; // Loop without shuffle
                    } else if (shuffle) {
                        loopMode = 3; // Shuffle without repeat
                    } else {
                        loopMode = 4; // All disabled
                    }

                    // Store final values for use in lambda
                    final boolean finalRepeat = repeat;
                    final boolean finalShuffle = shuffle;
                    final boolean finalLoopOnce = loopOnce;

                    // Send command to device and update state on success
                    future = httpManager.setLoopMode(loopMode).thenApply(result -> {
                        if (result.isSuccess()) {
                            deviceState.setRepeat(finalRepeat);
                            deviceState.setShuffle(finalShuffle);
                            deviceState.setLoopOnce(finalLoopOnce);
                            updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_REPEAT,
                                    OnOffType.from(finalRepeat));
                            updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_SHUFFLE,
                                    OnOffType.from(finalShuffle));
                            updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_LOOP_ONCE,
                                    OnOffType.from(finalLoopOnce));
                        }
                        return result;
                    });
                    break;

                case BindingConstants.CHANNEL_INPUT_SOURCE:
                    if (command instanceof StringType) {
                        String requestedSource = command.toString();
                        String sourceCommand = BindingConstants.INPUT_SOURCE_COMMANDS.get(requestedSource);
                        if (sourceCommand != null) {
                            // Send command to switch input source and update state on success
                            future = httpManager.setPlaySource(sourceCommand).thenApply(result -> {
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

                case BindingConstants.CHANNEL_URL_PLAY:
                    if (command instanceof StringType) {
                        future = httpManager.play(command.toString());
                    }
                    break;

                case BindingConstants.CHANNEL_M3U_PLAY:
                    if (command instanceof StringType) {
                        future = httpManager.playM3u(command.toString());
                    }
                    break;

                case BindingConstants.CHANNEL_PRESET:
                    if (command instanceof DecimalType) {
                        future = httpManager.playPreset(((DecimalType) command).intValue());
                    }
                    break;

                case BindingConstants.CHANNEL_NOTIFICATION:
                    if (command instanceof StringType) {
                        future = httpManager.playNotification(command.toString());
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
     * Processes player status updates received from the device.
     * Updates internal state and channels for:
     * <ul>
     * <li>Playback mode and input source</li>
     * <li>Playback status (play/pause/stop)</li>
     * <li>Track metadata (title, artist, album)</li>
     * <li>Playback position and duration</li>
     * <li>Volume and mute state</li>
     * <li>Repeat and shuffle settings</li>
     * </ul>
     * 
     * @param json The JSON response from the device's status API
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
            boolean repeat = false;
            boolean shuffle = false;
            boolean loopOnce = false;

            // Parse loop mode:
            // 0: SHUFFLE: disabled, REPEAT: enabled - loop
            // 1: SHUFFLE: disabled, REPEAT: enabled - loop once
            // 2: SHUFFLE: enabled, REPEAT: enabled - loop
            // 3: SHUFFLE: enabled, REPEAT: disabled
            // 4: SHUFFLE: disabled, REPEAT: disabled
            // 5: SHUFFLE: enabled, REPEAT: enabled - loop once
            switch (loopMode) {
                case 0:
                    repeat = true;
                    break;
                case 1:
                    repeat = true;
                    loopOnce = true;
                    break;
                case 2:
                    repeat = true;
                    shuffle = true;
                    break;
                case 3:
                    shuffle = true;
                    break;
                case 5:
                    repeat = true;
                    shuffle = true;
                    loopOnce = true;
                    break;
                // case 4 is all disabled (default values)
            }

            deviceState.setRepeat(repeat);
            deviceState.setShuffle(shuffle);
            deviceState.setLoopOnce(loopOnce);

            updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_REPEAT,
                    OnOffType.from(repeat));
            updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_SHUFFLE,
                    OnOffType.from(shuffle));
            updateState(BindingConstants.GROUP_PLAYBACK + "#" + BindingConstants.CHANNEL_LOOP_ONCE,
                    OnOffType.from(loopOnce));
        } catch (NumberFormatException e) {
            logger.debug("[{}] Failed to parse loop mode: {}", config.getDeviceName(), e.getMessage());
        }
    }

    /**
     * Processes extended device status information.
     * Updates device information and network-related channels:
     * <ul>
     * <li>Device identification (name, MAC, UDN)</li>
     * <li>Firmware version</li>
     * <li>Network status (WiFi signal strength)</li>
     * <li>Multiroom configuration</li>
     * </ul>
     *
     * @param json The JSON response from the device's extended status API
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
     * Handles communication results from device interactions.
     * Implements a threshold-based approach to prevent status flickering:
     * <ul>
     * <li>Tracks consecutive communication failures</li>
     * <li>Updates Thing status to OFFLINE after threshold exceeded</li>
     * <li>Restores ONLINE status on successful communication</li>
     * </ul>
     *
     * @param success Whether the communication attempt was successful
     * @param source Description of the communication attempt (for logging)
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
     * Updates metadata for the current track.
     * Attempts to retrieve additional information like album art
     * when both artist and title are available.
     */
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

    /**
     * Releases all resources and stops device communication.
     * Ensures proper cleanup of all managers and scheduled tasks.
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

    /**
     * Updates a channel state and notifies the thing handler.
     * All state updates should go through this method to ensure consistency.
     *
     * @param channelId The full channel ID including group
     * @param state The new state to set
     */
    public void updateState(String channelId, State state) {
        thingHandler.handleStateUpdate(channelId, state);
    }

    /**
     * Updates the Thing status without additional detail or message.
     * Shorthand for {@link #handleStatusUpdate(ThingStatus, ThingStatusDetail, String)}.
     *
     * @param status The new Thing status
     */
    private void handleStatusUpdate(ThingStatus status) {
        thingHandler.handleStatusUpdate(status);
    }

    /**
     * Updates the Thing status with detail and message.
     * Delegates to the thing handler for actual status update.
     *
     * @param status The new Thing status
     * @param detail Additional detail about the status
     * @param msg Human-readable message explaining the status
     */
    private void handleStatusUpdate(ThingStatus status, ThingStatusDetail detail, String msg) {
        thingHandler.handleStatusUpdate(status, detail, msg);
    }

    /**
     * Safely extracts a string value from a JSON object.
     * Returns empty string if the key doesn't exist or value is null.
     *
     * @param obj The JSON object to extract from
     * @param key The key to look up
     * @return The string value or empty string if not found
     */
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

    /**
     * Safely extracts an integer value from a JSON object.
     * Returns default value if the key doesn't exist or value is invalid.
     *
     * @param obj The JSON object to extract from
     * @param key The key to look up
     * @param defaultValue Value to return if key not found or invalid
     * @return The integer value or defaultValue if not found/invalid
     */
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
     * Gets the current configuration for this device.
     *
     * @return The LinkPlay configuration
     */
    public LinkPlayConfiguration getConfig() {
        return config;
    }

    /**
     * Gets the thing handler associated with this device manager.
     *
     * @return The LinkPlay thing handler
     */
    public LinkPlayThingHandler getThingHandler() {
        return thingHandler;
    }
}
