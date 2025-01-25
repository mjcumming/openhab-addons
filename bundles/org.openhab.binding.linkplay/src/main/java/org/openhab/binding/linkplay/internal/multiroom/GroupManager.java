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
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.DeviceManager;
import org.openhab.binding.linkplay.internal.handler.LinkPlayThingHandler;
import org.openhab.binding.linkplay.internal.model.MultiroomState;
import org.openhab.binding.linkplay.internal.transport.http.CommandResult;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The {@link GroupManager} handles multiroom functionality for LinkPlay devices.
 * This includes role detection, command synchronization, and state updates.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class GroupManager {

    private final Logger logger = LoggerFactory.getLogger(GroupManager.class);

    private final DeviceManager deviceManager;
    private final ThingRegistry thingRegistry;
    private final MultiroomState state;

    public GroupManager(DeviceManager deviceManager, ThingRegistry thingRegistry) {
        this.deviceManager = deviceManager;
        this.thingRegistry = thingRegistry;
        this.state = new MultiroomState();
    }

    /**
     * Handle multiroom-related commands from DeviceManager
     */
    public void handleCommand(String channelId, Command command) {
        switch (channelId) {
            case CHANNEL_JOIN:
                if (command instanceof StringType) {
                    String masterIP = command.toString();
                    if (masterIP.isEmpty() || state.isMaster()) {
                        logger.debug("[{}] Invalid join group request - masterIP empty or device is already master",
                                deviceManager.getConfig().getDeviceName());
                        return;
                    }
                    deviceManager.getHttpManager().joinGroup(masterIP).thenAccept(result -> {
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
                if (command instanceof OnOffType && command == OnOffType.ON) {
                    if (state.isMaster()) {
                        // Store current slave IPs before ungrouping
                        Set<String> previousSlaves = new HashSet<>(Arrays.asList(state.getSlaveIPs().split(",")));

                        deviceManager.getHttpManager().ungroup().thenAccept(result -> {
                            if (result.isSuccess()) {
                                // Update our state immediately
                                state.setStandaloneState();
                                updateChannels();

                                // Notify all previous slaves to update their status
                                for (String slaveIP : previousSlaves) {
                                    if (!slaveIP.isEmpty()) {
                                        Thing slaveThing = findThingByIP(slaveIP);
                                        if (slaveThing != null && slaveThing
                                                .getHandler() instanceof LinkPlayThingHandler slaveHandler) {
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
                        logger.info("[{}] Cannot ungroup - device is a slave",
                                deviceManager.getConfig().getDeviceName());
                    }
                }
                break;

            case CHANNEL_LEAVE:
                if (command instanceof OnOffType && command == OnOffType.ON) {
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
                                    masterDeviceManager.getHttpManager().kickoutSlave(myIP).thenAccept(result -> {
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
                    deviceManager.getHttpManager().kickoutSlave(slaveIP).thenAccept(result -> {
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
                    int volume = ((PercentType) command).intValue();
                    if (!state.isMaster()) {
                        logger.debug("[{}] Cannot set group volume - device is not master",
                                deviceManager.getConfig().getDeviceName());
                        return;
                    }

                    List<CompletableFuture<CommandResult>> volumeCommands = new ArrayList<>();

                    // Add master volume command
                    volumeCommands.add(deviceManager.getHttpManager().setVolume(volume));

                    // Add slave volume commands
                    for (String slaveIP : state.getSlaveIPs().split(",")) {
                        if (!slaveIP.isEmpty()) {
                            Thing slaveThing = findThingByIP(slaveIP);
                            if (slaveThing != null
                                    && slaveThing.getHandler() instanceof LinkPlayThingHandler slaveHandler) {
                                DeviceManager slaveDeviceManager = slaveHandler.getDeviceManager();
                                if (slaveDeviceManager != null) {
                                    volumeCommands
                                            .add(slaveDeviceManager.getHttpManager().setSlaveVolume(slaveIP, volume));
                                }
                            }
                        }
                    }

                    // Wait for all volume commands to complete
                    CompletableFuture.allOf(volumeCommands.toArray(new CompletableFuture[0])).thenAccept(v -> {
                        boolean allSuccess = true;
                        List<String> failures = new ArrayList<>();

                        // Check results
                        for (CompletableFuture<CommandResult> future : volumeCommands) {
                            try {
                                CommandResult result = future.get();
                                if (!result.isSuccess()) {
                                    allSuccess = false;
                                    String message = result.getErrorMessage();
                                    failures.add(message != null ? message : "Unknown error");
                                }
                            } catch (Exception e) {
                                allSuccess = false;
                                String message = e.getMessage();
                                failures.add(message != null ? message : "Unknown error");
                            }
                        }

                        if (allSuccess) {
                            deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_GROUP_VOLUME,
                                    new PercentType(volume));
                            logger.debug("[{}] Successfully set group volume to {}",
                                    deviceManager.getConfig().getDeviceName(), volume);
                        } else {
                            logger.warn("[{}] Failed to set group volume. Errors: {}",
                                    deviceManager.getConfig().getDeviceName(), String.join(", ", failures));
                        }
                    });
                }
                break;

            case CHANNEL_GROUP_MUTE:
                if (command instanceof OnOffType) {
                    boolean mute = command == OnOffType.ON;
                    if (!state.isMaster()) {
                        logger.debug("[{}] Cannot set group mute - device is not master",
                                deviceManager.getConfig().getDeviceName());
                        return;
                    }

                    List<CompletableFuture<CommandResult>> muteCommands = new ArrayList<>();

                    // Add master mute command
                    muteCommands.add(deviceManager.getHttpManager().setMute(mute));

                    // Add slave mute commands
                    for (String slaveIP : state.getSlaveIPs().split(",")) {
                        if (!slaveIP.isEmpty()) {
                            Thing slaveThing = findThingByIP(slaveIP);
                            if (slaveThing != null
                                    && slaveThing.getHandler() instanceof LinkPlayThingHandler slaveHandler) {
                                DeviceManager slaveDeviceManager = slaveHandler.getDeviceManager();
                                if (slaveDeviceManager != null) {
                                    muteCommands.add(slaveDeviceManager.getHttpManager().setSlaveMute(slaveIP, mute));
                                }
                            }
                        }
                    }

                    // Wait for all mute commands to complete
                    CompletableFuture.allOf(muteCommands.toArray(new CompletableFuture[0])).thenAccept(v -> {
                        boolean allSuccess = true;
                        List<String> failures = new ArrayList<>();

                        // Check results
                        for (CompletableFuture<CommandResult> future : muteCommands) {
                            try {
                                CommandResult result = future.get();
                                if (!result.isSuccess()) {
                                    allSuccess = false;
                                    String message = result.getErrorMessage();
                                    failures.add(message != null ? message : "Unknown error");
                                }
                            } catch (Exception e) {
                                allSuccess = false;
                                String message = e.getMessage();
                                failures.add(message != null ? message : "Unknown error");
                            }
                        }

                        if (allSuccess) {
                            deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_GROUP_MUTE, OnOffType.from(mute));
                            logger.debug("[{}] Successfully set group mute to {}",
                                    deviceManager.getConfig().getDeviceName(), mute);
                        } else {
                            logger.warn("[{}] Failed to set group mute. Errors: {}",
                                    deviceManager.getConfig().getDeviceName(), String.join(", ", failures));
                        }
                    });
                }
                break;
        }
    }

    /**
     * Process device status updates for multiroom functionality
     */
    public void handleDeviceStatus(JsonObject status) {
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
            if (ourIP != null && !ourIP.isEmpty()) {
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
            deviceManager.getHttpManager().getSlaveList().thenAccept(result -> {
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
                if (myIP != null && !myIP.isEmpty()) {
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

        // Ensure switches are OFF at startup/update
        deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_UNGROUP, OnOffType.OFF);
        deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_LEAVE, OnOffType.OFF);
    }

    private void updateMultiroomStatus() {
        // Only use this method when we need to force a full status update
        deviceManager.getHttpManager().getSlaveList().thenAccept(result -> {
            if (result.isSuccess()) {
                JsonObject response = result.getResponse();
                if (response != null) {
                    handleDeviceStatus(response);
                }
            } else {
                logger.warn("[{}] Failed to get slave list: {}", deviceManager.getConfig().getDeviceName(),
                        result.getErrorMessage());
            }
        });
    }

    public void dispose() {
        logger.debug("[{}] Disposing GroupManager", deviceManager.getConfig().getDeviceName());
    }

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
}
