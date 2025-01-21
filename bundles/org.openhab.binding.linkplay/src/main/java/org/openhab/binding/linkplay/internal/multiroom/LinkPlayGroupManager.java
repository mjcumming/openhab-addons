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
import org.openhab.binding.linkplay.internal.LinkPlayDeviceManager;
import org.openhab.binding.linkplay.internal.model.LinkPlayMultiroomState;
import org.openhab.binding.linkplay.internal.transport.http.LinkPlayHttpManager;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
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
    private final LinkPlayHttpManager httpManager;
    private final LinkPlayMultiroomState state;

    // API Command constants
    private static final String API_GET_SLAVE_LIST = "multiroom:getSlaveList";
    private static final String API_JOIN_GROUP = "ConnectMasterAp:JoinGroupMaster:eth%s:wifi0.0.0.0";
    private static final String API_UNGROUP = "multiroom:Ungroup";
    private static final String API_SLAVE_VOLUME = "multiroom:SlaveVolume:%s:%d";
    private static final String API_SLAVE_MUTE = "multiroom:SlaveMute:%s:%d";
    private static final String API_SLAVE_KICKOUT = "multiroom:SlaveKickout:%s";

    public LinkPlayGroupManager(LinkPlayDeviceManager deviceManager) {
        this.deviceManager = deviceManager;
        this.httpManager = deviceManager.getHttpManager();
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
                    // Reset switch to OFF state
                    deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_UNGROUP, OnOffType.OFF);
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
                            JsonObject response = httpManager.sendCommand(String.format(API_SLAVE_KICKOUT, myIP),
                                    masterIP);
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
                    // Always reset switch to OFF state
                    deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_LEAVE, OnOffType.OFF);
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
                    setGroupMute(command == OnOffType.ON);
                }
                break;
        }
    }

    /**
     * Process device status updates for multiroom functionality
     */
    public void handleDeviceStatus(JsonObject status) {
        boolean isGrouped = status.has("group") && status.get("group").getAsInt() == 1;

        if (!isGrouped) {
            // Check if this device might be a master by querying slave list
            JsonObject slaveListStatus = httpManager.sendCommand(API_GET_SLAVE_LIST);
            if (slaveListStatus != null && slaveListStatus.has("slave_list")
                    && !slaveListStatus.get("slave_list").getAsJsonArray().isEmpty()) {
                handleMasterStatus(slaveListStatus);
            } else {
                state.setStandaloneState();
            }
            updateChannels();
            return;
        }

        if (status.has("master_ip") || status.has("host_ip")) {
            handleSlaveStatus(status);
        } else {
            // This device might be a master - query for slave list
            JsonObject slaveListStatus = httpManager.sendCommand(API_GET_SLAVE_LIST);
            if (slaveListStatus != null && slaveListStatus.has("slave_list")) {
                handleMasterStatus(slaveListStatus);
            } else {
                // No slave list found, treat as standalone
                state.setStandaloneState();
            }
        }

        // Update group name if available
        if (status.has("GroupName")) {
            String groupName = status.get("GroupName").getAsString();
            if (!groupName.isEmpty()) {
                state.setGroupName(groupName);
            }
        }

        updateChannels();
    }

    private void handleMasterStatus(JsonObject status) {
        state.setMasterState();
        if (status.has("slave_list")) {
            JsonArray slaveList = status.getAsJsonArray("slave_list");
            if (!slaveList.isEmpty()) {
                List<String> slaveIPs = new ArrayList<>();
                for (JsonElement slave : slaveList) {
                    JsonObject slaveObj = slave.getAsJsonObject();
                    if (slaveObj.has("ip")) {
                        slaveIPs.add(slaveObj.get("ip").getAsString());
                    }
                }
                // Convert list to comma-separated string
                state.setSlaveIPs(String.join(",", slaveIPs));

                // Also add each slave with default values for volume/mute
                for (String ip : slaveIPs) {
                    state.addSlave(ip, "", 50, false); // Default volume 50%, unmuted
                }
            }
        }
        updateChannels();
    }

    private void handleSlaveStatus(JsonObject status) {
        String masterIP = status.has("master_ip") ? status.get("master_ip").getAsString()
                : status.get("host_ip").getAsString();

        if (masterIP.isEmpty()) {
            logger.warn("[{}] Invalid master IP received in slave status update",
                    deviceManager.getConfig().getDeviceName());
            state.setStandaloneState();
            return;
        }

        state.setSlaveState(masterIP);
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
        // Store current slave IPs before ungrouping
        Set<String> previousSlaves = new HashSet<>(Arrays.asList(state.getSlaveIPs().split(",")));
        String masterIP = deviceManager.getConfig().getIpAddress();

        JsonObject response = httpManager.sendCommand(API_UNGROUP);
        if (response != null) {
            logger.debug("[{}] Successfully sent ungroup command", deviceManager.getConfig().getDeviceName());

            // Force status update on this device (master)
            httpManager.sendCommand("getStatusEx");

            // Force status update on all previous slaves
            for (String slaveIP : previousSlaves) {
                if (!slaveIP.isEmpty()) {
                    // Use the device's HTTP manager to send command to slave
                    deviceManager.getHttpManager().sendCommand("getStatusEx");
                }
            }

            // Update local state
            updateMultiroomStatus();
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

    public void setGroupVolume(int volume) {
        if (!state.isMaster()) {
            logger.debug("[{}] Cannot set group volume - device is not master",
                    deviceManager.getConfig().getDeviceName());
            return;
        }

        for (String slaveIP : state.getSlaveIPs().split(",")) {
            if (!slaveIP.isEmpty()) {
                executeCommand(String.format(API_SLAVE_VOLUME, slaveIP, volume), "Set volume for slave " + slaveIP);
                state.updateSlaveVolume(slaveIP, volume);
            }
        }
    }

    public void setGroupMute(boolean mute) {
        if (!state.isMaster()) {
            logger.debug("[{}] Cannot set group mute - device is not master",
                    deviceManager.getConfig().getDeviceName());
            return;
        }

        for (String slaveIP : state.getSlaveIPs().split(",")) {
            if (!slaveIP.isEmpty()) {
                executeCommand(String.format(API_SLAVE_MUTE, slaveIP, mute ? 1 : 0), "Set mute for slave " + slaveIP);
                state.updateSlaveMute(slaveIP, mute);
            }
        }
    }

    private void executeCommand(String command, String successMessage) {
        try {
            JsonObject response = httpManager.sendCommand(command);
            if (response != null) {
                logger.debug("[{}] {}", deviceManager.getConfig().getDeviceName(), successMessage);
                updateMultiroomStatus();
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
        JsonObject status = httpManager.sendCommand(API_GET_SLAVE_LIST);
        if (status != null) {
            handleDeviceStatus(status);
        }
    }

    public void dispose() {
        logger.debug("[{}] Disposing LinkPlayGroupManager", deviceManager.getConfig().getDeviceName());
    }
}
