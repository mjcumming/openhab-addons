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

import static org.openhab.binding.linkplay.internal.LinkPlayBindingConstants.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.LinkPlayDeviceManager;
import org.openhab.binding.linkplay.internal.handler.LinkPlayThingHandler;
import org.openhab.binding.linkplay.internal.model.LinkPlayMultiroomState;
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
 * The {@link LinkPlayGroupManager} handles multiroom functionality for LinkPlay devices.
 * This includes role detection, command synchronization, and state updates.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayGroupManager {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayGroupManager.class);

    private final LinkPlayDeviceManager deviceManager;
    private final ThingRegistry thingRegistry;
    private final LinkPlayMultiroomState state;

    // API Command constants
    private static final String API_GET_SLAVE_LIST = "multiroom:getSlaveList";
    private static final String API_JOIN_GROUP = "ConnectMasterAp:JoinGroupMaster:eth%s:wifi0.0.0.0";
    private static final String API_UNGROUP = "multiroom:Ungroup";
    @SuppressWarnings("unused")
    private static final String API_SLAVE_VOLUME = "/httpapi.asp?command=setPlayerCmd:slave_volume:";
    @SuppressWarnings("unused")
    private static final String API_SLAVE_MUTE = "/httpapi.asp?command=setPlayerCmd:slave_mute:";
    private static final String API_SLAVE_KICKOUT = "multiroom:SlaveKickout:%s";
    @SuppressWarnings("unused")
    private static final String API_SET_MUTE = "/httpapi.asp?command=setPlayerCmd:mute:";

    public LinkPlayGroupManager(LinkPlayDeviceManager deviceManager, ThingRegistry thingRegistry) {
        this.deviceManager = deviceManager;
        this.thingRegistry = thingRegistry;
        this.state = new LinkPlayMultiroomState();
    }

    /**
     * Handle multiroom-related commands from DeviceManager
     */
    public void handleCommand(String channelId, Command command) {
        switch (channelId) {
            case CHANNEL_JOIN:
                if (command instanceof StringType) {
                    joinGroup(command.toString());
                }
                break;
            case CHANNEL_UNGROUP:
                if (command instanceof OnOffType && command == OnOffType.ON) {
                    if (state.isMaster()) {
                        ungroup();
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
                        // Get our own IP and have the master kick us
                        String masterIP = state.getMasterIP();
                        String myIP = deviceManager.getConfig().getIpAddress();
                        if (!masterIP.isEmpty() && !myIP.isEmpty()) {
                            // Send kickout command to the master device
                            JsonObject response = deviceManager.getHttpManager()
                                    .sendCommand(String.format(API_SLAVE_KICKOUT, myIP), masterIP);
                            if (response != null) {
                                logger.debug("[{}] Successfully requested to leave group via master kickout",
                                        deviceManager.getConfig().getDeviceName());
                                updateMultiroomStatus();
                            }
                        } else {
                            logger.warn("[{}] Cannot leave group - missing master IP or device IP",
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
                    kickSlave(command.toString());
                }
                break;
            case CHANNEL_GROUP_VOLUME:
                if (command instanceof PercentType) {
                    setGroupVolume(((PercentType) command).intValue());
                }
                break;
            case CHANNEL_GROUP_MUTE:
                if (command instanceof OnOffType) {
                    handleGroupMute(command == OnOffType.ON);
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
            String newMasterIP = status.has("master_ip") ? status.get("master_ip").getAsString()
                    : status.get("host_ip").getAsString();

            // Only update if master IP has changed
            if (!newMasterIP.equals(state.getMasterIP())) {
                handleSlaveStatus(status);
            }
        } else {
            // If we were previously a slave, notify the old master
            if (state.isSlave()) {
                String oldMasterIP = state.getMasterIP();
                Thing masterThing = findThingByIP(oldMasterIP);
                if (masterThing != null && masterThing.getHandler() instanceof LinkPlayThingHandler masterHandler) {
                    LinkPlayDeviceManager masterDeviceManager = masterHandler.getDeviceManager();
                    if (masterDeviceManager != null) {
                        // Get our IP so master can remove us from its slave list
                        String ourIP = deviceManager.getConfig().getIpAddress();
                        logger.debug("[{}] Notifying master {} that we left the group",
                                deviceManager.getConfig().getDeviceName(), oldMasterIP);

                        // Update master's slave list using existing list
                        String currentSlaveIPs = masterDeviceManager.getGroupManager().state.getSlaveIPs();
                        List<String> updatedSlaveIPs = new ArrayList<>();

                        // Build new slave list excluding our IP
                        for (String slaveIP : currentSlaveIPs.split(",")) {
                            if (!slaveIP.isEmpty() && !ourIP.equals(slaveIP)) {
                                updatedSlaveIPs.add(slaveIP);
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
            boolean isMaster = false;

            for (Thing thing : thingRegistry.getAll()) {
                if (THING_TYPE_MEDIASTREAMER.equals(thing.getThingTypeUID())
                        && thing.getHandler() instanceof LinkPlayThingHandler handler) {
                    LinkPlayDeviceManager otherDevice = handler.getDeviceManager();
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

        updateChannels();
    }

    private void handleMasterStatus() {
        boolean wasMaster = state.isMaster();
        state.setMasterState();

        // Only fetch slave list if we weren't already a master
        if (!wasMaster) {
            JsonObject slaveListStatus = deviceManager.getHttpManager().sendCommand(API_GET_SLAVE_LIST);
            if (slaveListStatus != null && slaveListStatus.has("slave_list")) {
                JsonArray slaveList = slaveListStatus.getAsJsonArray("slave_list");
                if (!slaveList.isEmpty()) {
                    List<String> slaveIPs = new ArrayList<>();
                    for (JsonElement slave : slaveList) {
                        JsonObject slaveObj = slave.getAsJsonObject();
                        if (slaveObj.has("ip")) {
                            slaveIPs.add(slaveObj.get("ip").getAsString());
                        }
                    }
                    state.setSlaveIPs(String.join(",", slaveIPs));
                }
            }
        }
    }

    private void handleSlaveStatus(JsonObject status) {
        String masterIP = status.get("master_ip").getAsString();
        if (masterIP.isEmpty()) {
            logger.warn("[{}] Invalid master IP received in slave status update",
                    deviceManager.getConfig().getDeviceName());
            return;
        }

        state.setSlaveState(masterIP);
        logger.debug("[{}] Set as slave to master: {}", deviceManager.getConfig().getDeviceName(), masterIP);

        // Find master device in registry and set it as master
        Thing masterThing = findThingByIP(masterIP);
        if (masterThing != null && masterThing.getHandler() instanceof LinkPlayThingHandler masterHandler) {
            LinkPlayDeviceManager masterDeviceManager = masterHandler.getDeviceManager();
            if (masterDeviceManager != null) {
                logger.debug("[{}] Found master device, setting master state",
                        deviceManager.getConfig().getDeviceName());
                masterDeviceManager.getGroupManager().handleMasterStatus();
            }
        }
    }

    // Command methods - pure business logic
    public void joinGroup(String masterIP) {
        if (masterIP.isEmpty() || state.isMaster()) {
            logger.debug("[{}] Invalid join group request - masterIP empty or device is already master",
                    deviceManager.getConfig().getDeviceName());
            return;
        }
        executeCommand(String.format(API_JOIN_GROUP, masterIP), "Successfully joined group with master " + masterIP);
    }

    /**
     * Handle ungroup command for master device
     */
    public void ungroup() {
        if (!state.isMaster()) {
            logger.debug("[{}] Cannot ungroup - device is not master", deviceManager.getConfig().getDeviceName());
            return;
        }

        // Store current slave IPs before ungrouping
        Set<String> previousSlaves = new HashSet<>(Arrays.asList(state.getSlaveIPs().split(",")));

        JsonObject response = deviceManager.getHttpManager().sendCommand(API_UNGROUP);
        if (response != null) {
            logger.debug("[{}] Successfully sent ungroup command", deviceManager.getConfig().getDeviceName());

            // Update our state immediately
            state.setStandaloneState();
            updateChannels();

            // Notify all previous slaves to update their status
            for (String slaveIP : previousSlaves) {
                if (!slaveIP.isEmpty()) {
                    Thing slaveThing = findThingByIP(slaveIP);
                    if (slaveThing != null && slaveThing.getHandler() instanceof LinkPlayThingHandler slaveHandler) {
                        LinkPlayDeviceManager slaveDeviceManager = slaveHandler.getDeviceManager();
                        if (slaveDeviceManager != null) {
                            slaveDeviceManager.getGroupManager().state.setStandaloneState();
                            slaveDeviceManager.getGroupManager().updateChannels();
                        }
                    }
                }
            }
        } else {
            logger.warn("[{}] Failed to send ungroup command", deviceManager.getConfig().getDeviceName());
        }
    }

    public void kickSlave(String slaveIP) {
        if (!state.isMaster()) {
            logger.debug("[{}] Cannot kick slave - device is not master", deviceManager.getConfig().getDeviceName());
            return;
        }
        executeCommand(String.format(API_SLAVE_KICKOUT, slaveIP), "Successfully kicked slave " + slaveIP);
    }

    /**
     * Calculate and set group volume based on all devices using Thing registry
     */
    private void calculateAndSetGroupVolume() {
        if (!state.isMaster()) {
            return;
        }

        // Start with master volume
        int totalVolume = deviceManager.getDeviceState().getVolume();
        int deviceCount = 1;

        // Add volumes from all slave devices
        for (String slaveIP : state.getSlaveIPs().split(",")) {
            if (!slaveIP.isEmpty()) {
                Thing slaveThing = findThingByIP(slaveIP);
                if (slaveThing != null && slaveThing.getHandler() instanceof LinkPlayThingHandler slaveHandler) {
                    LinkPlayDeviceManager slaveDeviceManager = slaveHandler.getDeviceManager();
                    if (slaveDeviceManager != null) {
                        totalVolume += slaveDeviceManager.getDeviceState().getVolume();
                        deviceCount++;
                    }
                }
            }
        }

        // Calculate average volume
        if (deviceCount > 0) {
            int groupVolume = totalVolume / deviceCount;
            deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_GROUP_VOLUME, new PercentType(groupVolume));
        }
    }

    /**
     * Handle individual device volume change
     * 
     * @param deviceIP IP of the device whose volume changed
     * @param newVolume New volume value (0-100)
     */
    public void handleDeviceVolumeChange(String deviceIP, int newVolume) {
        calculateAndSetGroupVolume();
    }

    public void setGroupVolume(int volume) {
        if (!state.isMaster()) {
            logger.debug("[{}] Cannot set group volume - device is not master",
                    deviceManager.getConfig().getDeviceName());
            return;
        }

        // Set master volume
        deviceManager.getHttpManager().sendCommand("setPlayerCmd:vol:" + volume);

        // Set volume on all slaves through their handlers
        for (String slaveIP : state.getSlaveIPs().split(",")) {
            if (!slaveIP.isEmpty()) {
                Thing slaveThing = findThingByIP(slaveIP);
                if (slaveThing != null && slaveThing.getHandler() instanceof LinkPlayThingHandler slaveHandler) {
                    LinkPlayDeviceManager slaveDeviceManager = slaveHandler.getDeviceManager();
                    if (slaveDeviceManager != null) {
                        slaveDeviceManager.getHttpManager().sendCommand("setPlayerCmd:vol:" + volume);
                    }
                }
            }
        }
    }

    private void calculateAndSetGroupMute() {
        if (!state.isMaster()) {
            return;
        }

        // Start with master mute state
        boolean allMuted = deviceManager.getDeviceState().isMute();

        // Check all slaves through their handlers
        for (String slaveIP : state.getSlaveIPs().split(",")) {
            if (!slaveIP.isEmpty()) {
                Thing slaveThing = findThingByIP(slaveIP);
                if (slaveThing != null && slaveThing.getHandler() instanceof LinkPlayThingHandler slaveHandler) {
                    LinkPlayDeviceManager slaveDeviceManager = slaveHandler.getDeviceManager();
                    if (slaveDeviceManager != null && !slaveDeviceManager.getDeviceState().isMute()) {
                        allMuted = false;
                        break;
                    }
                }
            }
        }

        deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_GROUP_MUTE, OnOffType.from(allMuted));
    }

    /**
     * Handle mute command for the entire group
     * 
     * @param mute true to mute, false to unmute
     */
    public void handleGroupMute(boolean mute) {
        if (!state.isMaster()) {
            logger.debug("[{}] Cannot set group mute - device is not master",
                    deviceManager.getConfig().getDeviceName());
            return;
        }

        // Set master mute
        deviceManager.getHttpManager().sendCommand(String.format("setPlayerCmd:mute:%d", mute ? 1 : 0));

        // Set mute on all slaves through their handlers
        for (String slaveIP : state.getSlaveIPs().split(",")) {
            if (!slaveIP.isEmpty()) {
                Thing slaveThing = findThingByIP(slaveIP);
                if (slaveThing != null && slaveThing.getHandler() instanceof LinkPlayThingHandler slaveHandler) {
                    LinkPlayDeviceManager slaveDeviceManager = slaveHandler.getDeviceManager();
                    if (slaveDeviceManager != null) {
                        slaveDeviceManager.getHttpManager()
                                .sendCommand(String.format("setPlayerCmd:mute:%d", mute ? 1 : 0));
                    }
                }
            }
        }
    }

    private void executeCommand(String command, String successMessage) {
        try {
            JsonObject response = deviceManager.getHttpManager().sendCommand(command);
            if (response != null) {
                // Check for text responses that may indicate success/failure
                if (response.has("status")) {
                    String status = response.get("status").getAsString();
                    if ("OK".equalsIgnoreCase(status) || "success".equalsIgnoreCase(status)) {
                        logger.debug("[{}] {}", deviceManager.getConfig().getDeviceName(), successMessage);
                        // Don't call updateMultiroomStatus() here - let specific commands handle their updates
                    } else {
                        logger.warn("[{}] Command failed with status: {}", deviceManager.getConfig().getDeviceName(),
                                status);
                    }
                } else {
                    // Treat non-empty response without status as success
                    logger.debug("[{}] {}", deviceManager.getConfig().getDeviceName(), successMessage);
                }
            } else {
                logger.warn("[{}] No response received for command: {}", deviceManager.getConfig().getDeviceName(),
                        command);
            }
        } catch (Exception e) {
            logger.warn("[{}] Failed to execute group command: {}", deviceManager.getConfig().getDeviceName(),
                    e.getMessage());
        }
    }

    private void updateChannels() {
        deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_ROLE, new StringType(state.getRole()));
        deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_MASTER_IP, new StringType(state.getMasterIP()));
        deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_SLAVE_IPS, new StringType(state.getSlaveIPs()));
        deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_GROUP_NAME, new StringType(state.getGroupName()));
        // Ensure switches are OFF at startup/update
        deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_UNGROUP, OnOffType.OFF);
        deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_LEAVE, OnOffType.OFF);
    }

    private void updateMultiroomStatus() {
        // Only use this method when we need to force a full status update
        JsonObject status = deviceManager.getHttpManager().sendCommand(API_GET_SLAVE_LIST);
        if (status != null) {
            handleDeviceStatus(status);
        }
    }

    public void dispose() {
        logger.debug("[{}] Disposing LinkPlayGroupManager", deviceManager.getConfig().getDeviceName());
    }

    private @Nullable Thing findThingByIP(String ipAddress) {
        for (Thing thing : thingRegistry.getAll()) {
            if (THING_TYPE_MEDIASTREAMER.equals(thing.getThingTypeUID())) {
                String thingIP = (String) thing.getConfiguration().get(CONFIG_IP_ADDRESS);
                if (ipAddress.equals(thingIP)) {
                    return thing;
                }
            }
        }
        return null;
    }
}
