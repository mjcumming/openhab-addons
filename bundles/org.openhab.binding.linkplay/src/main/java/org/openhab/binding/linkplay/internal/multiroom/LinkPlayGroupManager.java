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
                    ungroup();
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
            state.setStandaloneState();
            updateChannels();
            return;
        }

        if (status.has("master_ip") || status.has("host_ip")) {
            handleSlaveStatus(status);
        } else if (status.has("slave_list")) {
            handleMasterStatus(status);
        }

        // Update group name if available
        if (status.has("GroupName")) {
            String groupName = status.get("GroupName").getAsString();
            if (groupName != null && !groupName.isEmpty()) {
                state.setGroupName(groupName);
            }
        }

        updateChannels();
    }

    private void handleMasterStatus(JsonObject status) {
        state.setMasterState();
        if (status.has("slave_list")) {
            processSlaveList(status.getAsJsonArray("slave_list"));
        }
        updateChannels();
    }

    private void handleSlaveStatus(JsonObject status) {
        String masterIP = status.has("master_ip") ? status.get("master_ip").getAsString()
                : status.get("host_ip").getAsString();

        if (masterIP == null || masterIP.isEmpty()) {
            logger.warn("[{}] Invalid master IP received in slave status update",
                    deviceManager.getConfig().getDeviceName());
            state.setStandaloneState();
            return;
        }

        state.setSlaveState(masterIP);
    }

    private void processSlaveList(JsonArray slaveList) {
        state.clearSlaves();
        for (JsonElement slave : slaveList) {
            JsonObject slaveObj = slave.getAsJsonObject();
            String ip = slaveObj.get("ip").getAsString();
            String name = slaveObj.get("name").getAsString();
            int volume = slaveObj.get("volume").getAsInt();
            boolean muted = slaveObj.get("mute").getAsBoolean();
            state.addSlave(ip, name, volume, muted);
        }
    }

    // Command methods - pure business logic
    public void joinGroup(String masterIP) {
        if (masterIP == null || masterIP.isEmpty() || state.isMaster()) {
            logger.debug("[{}] Invalid join group request - masterIP empty or device is already master",
                    deviceManager.getConfig().getDeviceName());
            return;
        }
        executeCommand(String.format(API_JOIN_GROUP, masterIP), "Successfully joined group with master " + masterIP);
    }

    public void ungroup() {
        if (state.isStandalone()) {
            logger.debug("[{}] Device is already standalone", deviceManager.getConfig().getDeviceName());
            return;
        }
        executeCommand(API_UNGROUP, "Successfully left group");
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
