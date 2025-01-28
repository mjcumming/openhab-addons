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
package org.openhab.binding.linkplay.internal.multiroom;

import static org.openhab.binding.linkplay.internal.BindingConstants.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.DeviceManager;
import org.openhab.binding.linkplay.internal.handler.LinkPlayThingHandler;
import org.openhab.binding.linkplay.internal.model.MultiroomState;
import org.openhab.binding.linkplay.internal.transport.http.CommandResult;
import org.openhab.binding.linkplay.internal.transport.http.HttpManager;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The {@link GroupManager} handles multiroom functionality for LinkPlay devices.
 * It manages:
 * - Device role detection (master/slave/standalone)
 * - Group command synchronization across devices
 * - Multiroom state updates and channel management
 * - Group volume and mute control
 * - Device joining/leaving group operations
 *
 * @author Michael Cumming - Initial contribution
 * @see org.openhab.binding.linkplay.internal.DeviceManager
 * @see org.openhab.binding.linkplay.internal.handler.LinkPlayThingHandler
 */
@NonNullByDefault
public class GroupManager {

    private final Logger logger = LoggerFactory.getLogger(GroupManager.class);

    private final DeviceManager deviceManager;
    private final ThingRegistry thingRegistry;
    private final MultiroomState state;
    private final HttpManager httpManager;

    private static final String GROUP_MULTIROOM = "multiroom";
    private static final String CHANNEL_UNGROUP = "ungroup"; // Trigger channel for ungrouping devices
    private static final String CHANNEL_LEAVE = "leave"; // Trigger channel for leaving a group
    private static final String CHANNEL_GROUP_VOLUME = "groupVolume"; // Volume control for entire group
    private static final String CHANNEL_GROUP_MUTE = "groupMute"; // Mute control for entire group
    private static final String CHANNEL_KICKOUT = "kickout"; // Command channel for removing a slave
    private static final String CHANNEL_JOIN = "join"; // Command channel for joining a group

    public GroupManager(DeviceManager deviceManager, HttpManager httpManager, ThingRegistry thingRegistry) {
        this.deviceManager = deviceManager;
        this.httpManager = httpManager;
        this.thingRegistry = thingRegistry;
        this.state = new MultiroomState();
    }

    /**
     * Handle multiroom-related commands from DeviceManager.
     * Processes various channel commands including:
     * - join: Join a group with specified master IP
     * - ungroup: Disband current group (master only)
     * - leave: Leave current group (slave only)
     * - kickout: Remove a slave from group (master only)
     * - groupVolume: Set volume for all group devices
     * - groupMute: Set mute state for all group devices
     *
     * @param channelId The channel identifier receiving the command
     * @param command The command to process
     */
    public void handleCommand(String channelId, Command command) {
        // Extract base channel name for trigger channels
        String baseChannel = channelId.contains("#") ? channelId.split("#")[0] : channelId;

        // Handle REFRESH commands first
        if (command instanceof RefreshType) {
            handleRefreshCommand(baseChannel);
            return;
        }

        switch (baseChannel) {
            case CHANNEL_JOIN:
                if (command instanceof StringType) {
                    String masterIP = command.toString();
                    if (masterIP.isEmpty() || state.isMaster()) {
                        logger.debug("[{}] Invalid join group request - masterIP empty or device is already master",
                                deviceManager.getConfig().getDeviceName());
                        return;
                    }
                    httpManager.joinGroup(masterIP).thenAccept(result -> {
                        if (result.isSuccess()) {
                            // State will be updated via device status polling
                            logger.debug("[{}] Successfully sent join group command",
                                    deviceManager.getConfig().getDeviceName());
                        } else {
                            logger.warn("[{}] Failed to join group: {}", deviceManager.getConfig().getDeviceName(),
                                    result.getErrorMessage());
                        }
                    });
                }
                break;

            case CHANNEL_UNGROUP:
                // Now a trigger channel
                if (state.isMaster()) {
                    // Store current slave IPs before ungrouping
                    Set<String> previousSlaves = new HashSet<>(Arrays.asList(state.getSlaveIPs().split(",")));

                    httpManager.ungroup().thenAccept(result -> {
                        if (result.isSuccess()) {
                            // Update our state immediately
                            state.setStandaloneState();
                            updateChannels();

                            // Notify all previous slaves to update their status
                            for (String slaveIP : previousSlaves) {
                                if (!slaveIP.isEmpty()) {
                                    Thing slaveThing = findThingByIP(slaveIP);
                                    if (slaveThing != null
                                            && slaveThing.getHandler() instanceof LinkPlayThingHandler slaveHandler) {
                                        DeviceManager slaveDeviceManager = slaveHandler.getDeviceManager();
                                        if (slaveDeviceManager != null) {
                                            slaveDeviceManager.getGroupManager().state.setStandaloneState();
                                            slaveDeviceManager.getGroupManager().updateChannels();
                                        }
                                    }
                                }
                            }
                            logger.debug("[{}] Successfully sent ungroup command",
                                    deviceManager.getConfig().getDeviceName());
                        } else {
                            logger.warn("[{}] Failed to ungroup: {}", deviceManager.getConfig().getDeviceName(),
                                    result.getErrorMessage());
                        }
                    });
                } else if (state.isStandalone()) {
                    logger.info("[{}] Cannot ungroup - device is standalone",
                            deviceManager.getConfig().getDeviceName());
                } else {
                    logger.info("[{}] Cannot ungroup - device is a slave", deviceManager.getConfig().getDeviceName());
                }
                break;

            case CHANNEL_LEAVE:
                // Now a trigger channel
                if (state.isSlave()) {
                    String masterIP = state.getMasterIP();
                    String myIP = deviceManager.getConfig().getIpAddress();
                    if (myIP.isEmpty()) {
                        logger.warn("[{}] Cannot leave group - device IP is empty",
                                deviceManager.getConfig().getDeviceName());
                        return;
                    }
                    if (!masterIP.isEmpty()) {
                        // Find master device in registry
                        Thing masterThing = findThingByIP(masterIP);
                        if (masterThing != null
                                && masterThing.getHandler() instanceof LinkPlayThingHandler masterHandler) {
                            DeviceManager masterDeviceManager = masterHandler.getDeviceManager();
                            if (masterDeviceManager != null) {
                                // Send kickout command through master's HTTP manager
                                masterDeviceManager.getGroupManager().httpManager.kickoutSlave(myIP)
                                        .thenAccept(result -> {
                                            if (result.isSuccess()) {
                                                logger.debug(
                                                        "[{}] Successfully requested to leave group via master kickout",
                                                        deviceManager.getConfig().getDeviceName());
                                                updateMultiroomStatus();
                                            } else {
                                                logger.warn("[{}] Failed to leave group: {}",
                                                        deviceManager.getConfig().getDeviceName(),
                                                        result.getErrorMessage());
                                            }
                                        });
                            }
                        } else {
                            logger.warn("[{}] Cannot leave group - master device not found in registry",
                                    deviceManager.getConfig().getDeviceName());
                        }
                    } else {
                        logger.warn("[{}] Cannot leave group - missing master IP",
                                deviceManager.getConfig().getDeviceName());
                    }
                } else if (state.isStandalone()) {
                    logger.info("[{}] Cannot leave group - device is standalone",
                            deviceManager.getConfig().getDeviceName());
                } else {
                    logger.info("[{}] Cannot leave group - device is a master",
                            deviceManager.getConfig().getDeviceName());
                }
                break;

            case CHANNEL_KICKOUT:
                if (command instanceof StringType) {
                    String slaveIP = command.toString();
                    if (slaveIP.isEmpty() || !state.isMaster()) {
                        logger.debug("[{}] Cannot kick slave - invalid IP or device is not master",
                                deviceManager.getConfig().getDeviceName());
                        return;
                    }
                    String ourIP = deviceManager.getConfig().getIpAddress();
                    if (ourIP.isEmpty()) {
                        logger.debug("[{}] Cannot kickout - device IP is empty",
                                deviceManager.getConfig().getDeviceName());
                        return;
                    }
                    httpManager.kickoutSlave(slaveIP).thenAccept(result -> {
                        if (result.isSuccess()) {
                            // Update our slave list by removing the kicked slave
                            List<String> updatedSlaveIPs = new ArrayList<>();
                            for (String ip : state.getSlaveIPs().split(",")) {
                                if (!ip.isEmpty() && !slaveIP.equals(ip)) {
                                    updatedSlaveIPs.add(ip);
                                }
                            }

                            // If no slaves left, set to standalone, otherwise update slave list
                            if (updatedSlaveIPs.isEmpty()) {
                                state.setStandaloneState();
                            } else {
                                state.setMasterState();
                                state.setSlaveIPs(String.join(",", updatedSlaveIPs));
                            }
                            updateChannels();

                            logger.debug("[{}] Successfully kicked slave {} and updated slave list",
                                    deviceManager.getConfig().getDeviceName(), slaveIP);
                        } else {
                            logger.warn("[{}] Failed to kick slave {}: {}", deviceManager.getConfig().getDeviceName(),
                                    slaveIP, result.getErrorMessage());
                        }
                    });
                }
                break;

            case CHANNEL_GROUP_VOLUME:
                if (command instanceof PercentType) {
                    handleGroupVolumeCommand((PercentType) command);
                }
                break;

            case CHANNEL_GROUP_MUTE:
                if (command instanceof OnOffType) {
                    handleGroupMuteCommand((OnOffType) command);
                }
                break;
        }
    }

    /**
     * Handle REFRESH commands for multiroom channels
     */
    private void handleRefreshCommand(String channel) {
        switch (channel) {
            case CHANNEL_GROUP_VOLUME:
            case CHANNEL_GROUP_MUTE:
                if (state.isMaster()) {
                    handleGroupVolumeAndMuteRefresh();
                }
                break;

            case CHANNEL_ROLE:
            case CHANNEL_MASTER_IP:
            case CHANNEL_SLAVE_IPS:
            case CHANNEL_GROUP_NAME:
                // These channels are updated together via device status
                updateMultiroomStatus();
                break;

            default:
                logger.debug("[{}] Unhandled multiroom REFRESH command for channel: {}",
                        deviceManager.getConfig().getDeviceName(), channel);
                break;
        }
    }

    /**
     * Handle group volume and mute refresh from a single player status query
     */
    private void handleGroupVolumeAndMuteRefresh() {
        // For masters, get current status of all devices
        List<CompletableFuture<CommandResult>> statusQueries = new ArrayList<>();

        // Add master status query
        statusQueries.add(httpManager.getPlayerStatus());

        // Add slave status queries
        for (String slaveIP : state.getSlaveIPs().split(",")) {
            if (!slaveIP.isEmpty()) {
                Thing slaveThing = findThingByIP(slaveIP);
                if (slaveThing != null && slaveThing.getHandler() instanceof LinkPlayThingHandler slaveHandler) {
                    DeviceManager slaveDeviceManager = slaveHandler.getDeviceManager();
                    if (slaveDeviceManager != null) {
                        statusQueries.add(slaveDeviceManager.getGroupManager().httpManager.getPlayerStatus());
                    }
                }
            }
        }

        // Process all status queries
        CompletableFuture.allOf(statusQueries.toArray(new CompletableFuture[0])).thenAccept(v -> {
            int maxVolume = 0;
            boolean allMuted = true;

            for (CompletableFuture<CommandResult> future : statusQueries) {
                try {
                    CommandResult result = future.get();
                    if (!result.isSuccess()) {
                        continue;
                    }

                    @Nullable
                    JsonObject response = result.getResponse();
                    if (response == null) {
                        continue;
                    }

                    // Get volume and mute from player status
                    if (response.has("vol")) {
                        maxVolume = Math.max(maxVolume, response.get("vol").getAsInt());
                    }
                    if (response.has("mute")) {
                        allMuted &= "1".equals(response.get("mute").getAsString());
                    }
                } catch (Exception e) {
                    logger.warn("[{}] Error getting player status during REFRESH: {}",
                            deviceManager.getConfig().getDeviceName(), e.getMessage());
                }
            }

            // Update both group volume and mute states
            deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_GROUP_VOLUME, new PercentType(maxVolume));
            deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_GROUP_MUTE, OnOffType.from(allMuted));
        });
    }

    /**
     * Processes device status updates for multiroom functionality.
     * Updates the device's role (master/slave/standalone) and associated state based on
     * the status information received from the device.
     *
     * @param status JsonObject containing device status information. May include:
     *            - group: 1 if device is in a group, 0 if standalone
     *            - master_ip: IP of master device if in a group
     *            - host_ip: Alternative field for master IP
     */
    public void handleDeviceStatus(@Nullable JsonObject status) {
        if (status == null) {
            return;
        }
        boolean isGrouped = status.has("group") && status.get("group").getAsInt() == 1;

        if (isGrouped) {
            String newMasterIP = "";
            if (status.has("master_ip")) {
                JsonElement masterIpElement = status.get("master_ip");
                if (!masterIpElement.isJsonNull()) {
                    newMasterIP = masterIpElement.getAsString();
                }
            } else if (status.has("host_ip")) {
                JsonElement hostIpElement = status.get("host_ip");
                if (!hostIpElement.isJsonNull()) {
                    newMasterIP = hostIpElement.getAsString();
                }
            }

            // Only update if master IP has changed and is not empty
            if (!newMasterIP.isEmpty() && !newMasterIP.equals(state.getMasterIP())) {
                handleSlaveStatus(status);
            }
        } else {
            // If we were previously a slave, notify the old master
            if (state.isSlave()) {
                String oldMasterIP = state.getMasterIP();
                Thing masterThing = findThingByIP(oldMasterIP);
                if (masterThing != null && masterThing.getHandler() instanceof LinkPlayThingHandler masterHandler) {
                    DeviceManager masterDeviceManager = masterHandler.getDeviceManager();
                    if (masterDeviceManager != null) {
                        // Get our IP so master can remove us from its slave list
                        String myIP = deviceManager.getConfig().getIpAddress();
                        if (myIP.isEmpty()) {
                            return;
                        }

                        logger.debug("[{}] Notifying master {} that we left the group",
                                deviceManager.getConfig().getDeviceName(), oldMasterIP);

                        // Update master's slave list using existing list
                        String currentSlaveIPs = masterDeviceManager.getGroupManager().state.getSlaveIPs();
                        List<String> updatedSlaveIPs = new ArrayList<>();

                        // Build new slave list excluding our IP
                        if (!currentSlaveIPs.isEmpty()) {
                            for (String ip : currentSlaveIPs.split(",")) {
                                if (!ip.isEmpty() && !myIP.equals(ip)) {
                                    updatedSlaveIPs.add(ip);
                                }
                            }
                        }

                        // If no slaves left, set master to standalone
                        if (updatedSlaveIPs.isEmpty()) {
                            masterDeviceManager.getGroupManager().state.setStandaloneState();
                        } else {
                            masterDeviceManager.getGroupManager().state.setSlaveIPs(String.join(",", updatedSlaveIPs));
                        }
                        masterDeviceManager.getGroupManager().updateChannels();
                    }
                }
            }

            // Now check if we're a master by looking for slaves that point to us
            String ourIP = deviceManager.getConfig().getIpAddress();
            if (!ourIP.isEmpty()) {
                boolean isMaster = false;

                for (Thing thing : thingRegistry.getAll()) {
                    if (THING_TYPE_MEDIASTREAMER.equals(thing.getThingTypeUID())
                            && thing.getHandler() instanceof LinkPlayThingHandler handler) {
                        DeviceManager otherDevice = handler.getDeviceManager();
                        if (otherDevice != null && otherDevice.getGroupManager().state.isSlave()
                                && ourIP.equals(otherDevice.getGroupManager().state.getMasterIP())) {
                            isMaster = true;
                            break;
                        }
                    }
                }

                if (isMaster) {
                    // Only update if we weren't already a master
                    if (!state.isMaster()) {
                        handleMasterStatus();
                    }
                } else {
                    // No slaves point to us as master, we're standalone
                    state.setStandaloneState();
                }
            }
        }

        updateChannels();
    }

    private void handleMasterStatus() {
        boolean wasMaster = state.isMaster();
        state.setMasterState();

        // Only fetch slave list if we weren't already a master
        if (!wasMaster) {
            httpManager.getSlaveList().thenAccept(result -> {
                if (result.isSuccess()) {
                    JsonObject slaveListStatus = result.getResponse();
                    if (slaveListStatus != null && slaveListStatus.has("slave_list")) {
                        JsonArray slaveList = slaveListStatus.getAsJsonArray("slave_list");
                        if (!slaveList.isEmpty()) {
                            List<String> slaveIPs = new ArrayList<>();
                            int maxVolume = deviceManager.getDeviceState().getVolume();
                            boolean allMuted = deviceManager.getDeviceState().isMute();

                            for (JsonElement slave : slaveList) {
                                JsonObject slaveObj = slave.getAsJsonObject();
                                if (slaveObj.has("ip")) {
                                    String slaveIP = slaveObj.get("ip").getAsString();
                                    if (!slaveIP.isEmpty()) {
                                        slaveIPs.add(slaveIP);

                                        // Get slave volume and mute state
                                        Thing slaveThing = findThingByIP(slaveIP);
                                        if (slaveThing != null && slaveThing
                                                .getHandler() instanceof LinkPlayThingHandler slaveHandler) {
                                            DeviceManager slaveDeviceManager = slaveHandler.getDeviceManager();
                                            if (slaveDeviceManager != null) {
                                                maxVolume = Math.max(maxVolume,
                                                        slaveDeviceManager.getDeviceState().getVolume());
                                                allMuted = allMuted && slaveDeviceManager.getDeviceState().isMute();
                                            }
                                        }
                                    }
                                }
                            }
                            if (!slaveIPs.isEmpty()) {
                                state.setSlaveIPs(String.join(",", slaveIPs));

                                deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_GROUP_VOLUME,
                                        new PercentType(maxVolume));
                                deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_GROUP_MUTE,
                                        OnOffType.from(allMuted));
                            }
                        }
                    }
                }
            });
        }
    }

    private void handleSlaveStatus(JsonObject status) {
        JsonElement masterIpElement = status.get("master_ip");
        if (masterIpElement == null || masterIpElement.isJsonNull()) {
            logger.warn("[{}] Invalid master IP received in slave status update",
                    deviceManager.getConfig().getDeviceName());
            return;
        }

        String masterIP = masterIpElement.getAsString();
        if (masterIP.isEmpty()) {
            logger.warn("[{}] Empty master IP received in slave status update",
                    deviceManager.getConfig().getDeviceName());
            return;
        }

        state.setSlaveState(masterIP);
        logger.debug("[{}] Set as slave to master: {}", deviceManager.getConfig().getDeviceName(), masterIP);

        // Reset group volume to 0 when becoming a slave
        deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_GROUP_VOLUME, new PercentType(0));
        // Reset group mute when becoming a slave
        deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_GROUP_MUTE, OnOffType.OFF);

        // Find master device in registry and update its slave list
        Thing masterThing = findThingByIP(masterIP);
        if (masterThing != null && masterThing.getHandler() instanceof LinkPlayThingHandler masterHandler) {
            DeviceManager masterDeviceManager = masterHandler.getDeviceManager();
            if (masterDeviceManager != null) {
                // Get our IP to add to master's slave list
                String myIP = deviceManager.getConfig().getIpAddress();
                if (!myIP.isEmpty()) {
                    String currentSlaveIPs = masterDeviceManager.getGroupManager().state.getSlaveIPs();

                    // Add our IP to master's slave list if not already present
                    Set<String> slaveIPs = new HashSet<>();
                    if (!currentSlaveIPs.isEmpty()) {
                        slaveIPs.addAll(Arrays.asList(currentSlaveIPs.split(",")));
                    }
                    slaveIPs.add(myIP);

                    // Update master's state
                    masterDeviceManager.getGroupManager().state.setMasterState();
                    masterDeviceManager.getGroupManager().state.setSlaveIPs(String.join(",", slaveIPs));

                    // Update master's group volume and mute state
                    int masterVolume = masterDeviceManager.getDeviceState().getVolume();
                    int newSlaveVolume = deviceManager.getDeviceState().getVolume();
                    if (newSlaveVolume > masterVolume) {
                        masterDeviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_GROUP_VOLUME,
                                new PercentType(newSlaveVolume));
                    }

                    // Update master's group mute state (muted only if all devices are muted)
                    boolean masterMuted = masterDeviceManager.getDeviceState().isMute();
                    boolean allMuted = masterMuted;
                    for (String slaveIP : slaveIPs) {
                        if (!slaveIP.isEmpty()) {
                            Thing slaveThing = findThingByIP(slaveIP);
                            if (slaveThing != null
                                    && slaveThing.getHandler() instanceof LinkPlayThingHandler slaveHandler) {
                                DeviceManager slaveDeviceManager = slaveHandler.getDeviceManager();
                                if (slaveDeviceManager != null) {
                                    allMuted = allMuted && slaveDeviceManager.getDeviceState().isMute();
                                }
                            }
                        }
                    }
                    masterDeviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_GROUP_MUTE,
                            OnOffType.from(allMuted));

                    logger.debug("[{}] Updated master's slave list and group states",
                            deviceManager.getConfig().getDeviceName());
                }
            }
        }
    }

    /**
     * Updates all multiroom-related channels based on current state.
     * Channels updated:
     * - role: Current device role (master/slave/standalone)
     * - masterIP: IP address of master device (if slave)
     * - slaveIPs: Comma-separated list of slave IPs (if master)
     * - groupName: Name of the current group
     * - groupVolume/groupMute: Reset for standalone devices
     */
    private void updateChannels() {
        deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_ROLE, new StringType(state.getRole()));
        deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_MASTER_IP, new StringType(state.getMasterIP()));
        deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_SLAVE_IPS, new StringType(state.getSlaveIPs()));
        deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_GROUP_NAME, new StringType(state.getGroupName()));

        // Reset group volume and mute for standalone devices
        if (state.isStandalone()) {
            deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_GROUP_VOLUME, new PercentType(0));
            deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_GROUP_MUTE, OnOffType.OFF);
        }
    }

    /**
     * Updates the multiroom status by querying the device's extended status.
     * This is typically called after group operations or during initialization.
     */
    public void updateMultiroomStatus() {
        httpManager.getStatusEx().thenAccept(result -> {
            JsonObject response = result.getResponse();
            if (result.isSuccess() && response != null) {
                handleDeviceStatus(response);
            } else {
                logger.warn("[{}] Failed to update multiroom status: {}", deviceManager.getConfig().getDeviceName(),
                        result.getErrorMessage());
            }
        });
    }

    /**
     * Handles cleanup when the GroupManager is being disposed.
     * Currently just logs the disposal event.
     */
    public void dispose() {
        logger.debug("[{}] Disposing GroupManager", deviceManager.getConfig().getDeviceName());
    }

    /**
     * Finds a Thing in the registry by its IP address.
     * Used to locate master/slave devices for group operations.
     *
     * @param ipAddress IP address to search for
     * @return Thing if found, null otherwise
     */
    private @Nullable Thing findThingByIP(String ipAddress) {
        if (ipAddress.isEmpty()) {
            return null;
        }
        for (Thing thing : thingRegistry.getAll()) {
            if (THING_TYPE_MEDIASTREAMER.equals(thing.getThingTypeUID())) {
                Object configIp = thing.getConfiguration().get(CONFIG_IP_ADDRESS);
                if (configIp instanceof String thingIP && ipAddress.equals(thingIP)) {
                    return thing;
                }
            }
        }
        return null;
    }

    /**
     * Executes a command on all devices in a group and processes their responses.
     * Handles aggregation of results and updates the corresponding channel state.
     *
     * @param commands List of command futures to execute
     * @param channelSuffix Channel suffix to update with the result
     * @param resultProcessor Function to process individual command results
     */
    private void executeGroupCommand(List<CompletableFuture<CommandResult>> commands, String channelSuffix,
            Function<CommandResult, Optional<Boolean>> resultProcessor) {
        CompletableFuture.allOf(commands.toArray(new CompletableFuture[0])).thenAccept(v -> {
            boolean allSuccess = true;
            List<String> failures = new ArrayList<>();

            // Process results
            Optional<Boolean> finalState = Optional.empty();
            for (CompletableFuture<CommandResult> future : commands) {
                try {
                    CommandResult result = future.get();
                    if (!result.isSuccess()) {
                        allSuccess = false;
                        failures.add(result.getErrorMessage());
                    } else {
                        @Nullable
                        JsonObject response = result.getResponse();
                        if (response != null) {
                            Optional<Boolean> state = resultProcessor.apply(result);
                            if (state.isPresent()) {
                                finalState = finalState.isPresent() ? Optional.of(finalState.get() && state.get())
                                        : state;
                            }
                        }
                    }
                } catch (Exception e) {
                    allSuccess = false;
                    String message = e.getMessage();
                    failures.add(message != null ? message : e.getClass().getSimpleName());
                }
            }

            if (allSuccess && finalState.isPresent()) {
                deviceManager.updateState(GROUP_MULTIROOM + "#" + channelSuffix, OnOffType.from(finalState.get()));
                logger.debug("[{}] Successfully executed group command for {}",
                        deviceManager.getConfig().getDeviceName(), channelSuffix);
            } else {
                logger.warn("[{}] Failed to execute group command. Errors: {}",
                        deviceManager.getConfig().getDeviceName(), String.join(", ", failures));
            }
        });
    }

    /**
     * Collects commands to be executed on all devices in a group.
     * Builds a list of command futures for both master and slave devices.
     *
     * @param commandSupplier Function that generates the command for each device
     * @return List of command futures to be executed
     */
    private List<CompletableFuture<CommandResult>> collectGroupCommands(
            Function<DeviceManager, CompletableFuture<CommandResult>> commandSupplier) {
        List<CompletableFuture<CommandResult>> commands = new ArrayList<>();

        // Add master command
        commands.add(commandSupplier.apply(deviceManager));

        // Add slave commands
        for (String slaveIP : state.getSlaveIPs().split(",")) {
            if (!slaveIP.isEmpty()) {
                Thing slaveThing = findThingByIP(slaveIP);
                if (slaveThing != null && slaveThing.getHandler() instanceof LinkPlayThingHandler slaveHandler) {
                    DeviceManager slaveDeviceManager = slaveHandler.getDeviceManager();
                    if (slaveDeviceManager != null) {
                        commands.add(commandSupplier.apply(slaveDeviceManager));
                    }
                }
            }
        }

        return commands;
    }

    /**
     * Handles volume control for the entire group.
     * Sets the same volume level on all devices in the group.
     * Only available when device is master.
     *
     * @param command PercentType command containing the target volume level
     */
    private void handleGroupVolumeCommand(PercentType command) {
        if (!state.isMaster()) {
            logger.debug("[{}] Cannot set group volume - device is not master",
                    deviceManager.getConfig().getDeviceName());
            return;
        }

        int volume = command.intValue();
        List<CompletableFuture<CommandResult>> volumeCommands = collectGroupCommands(
                dm -> dm.getGroupManager().httpManager.setVolume(volume));

        executeGroupCommand(volumeCommands, CHANNEL_GROUP_VOLUME, result -> {
            @Nullable
            JsonObject response = result.getResponse();
            if (response != null && response.has("vol")) {
                return Optional.of(response.get("vol").getAsInt() == volume);
            }
            return Optional.empty();
        });
    }

    /**
     * Handles mute control for the entire group.
     * Sets the same mute state on all devices in the group.
     * Only available when device is master.
     *
     * @param command OnOffType command indicating desired mute state
     */
    private void handleGroupMuteCommand(OnOffType command) {
        if (!state.isMaster()) {
            logger.debug("[{}] Cannot set group mute - device is not master",
                    deviceManager.getConfig().getDeviceName());
            return;
        }

        boolean mute = command == OnOffType.ON;
        List<CompletableFuture<CommandResult>> muteCommands = collectGroupCommands(
                dm -> dm.getGroupManager().httpManager.setMute(mute));

        executeGroupCommand(muteCommands, CHANNEL_GROUP_MUTE, result -> {
            @Nullable
            JsonObject response = result.getResponse();
            if (response != null && response.has("mute")) {
                return Optional.of(response.get("mute").getAsInt() == 1);
            }
            return Optional.empty();
        });
    }
}
