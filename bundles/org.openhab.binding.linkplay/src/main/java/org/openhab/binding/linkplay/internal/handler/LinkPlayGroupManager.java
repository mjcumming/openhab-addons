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
package org.openhab.binding.linkplay.internal.handler;

import static org.openhab.binding.linkplay.internal.LinkPlayBindingConstants.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpManager;
import org.openhab.binding.linkplay.internal.model.MultiroomInfo;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private String ipAddress = ""; // Initialized to an empty string
    private final LinkPlayHttpManager httpManager;
    private final String deviceName;

    public LinkPlayGroupManager(LinkPlayHttpManager httpManager, String deviceName) {
        this.httpManager = httpManager;
        this.deviceName = deviceName;
    }

    public void initialize(String ipAddress) {
        if (!ipAddress.isEmpty()) {
            this.ipAddress = ipAddress;
            triggerGroupStateUpdate();
        } else {
            logger.warn("[{}] Cannot initialize group manager - IP address is empty", deviceName);
        }
    }

    /**
     * Trigger updates for the group state by querying the current device's status.
     */
    public void triggerGroupStateUpdate() {
        if (ipAddress.isEmpty()) {
            logger.warn("[{}] Cannot update group state - IP address is empty", deviceName);
            return;
        }

        try {
            @Nullable
            JsonObject response = httpManager.sendCommand("getStatusEx");
            if (response == null) {
                logger.warn("[{}] Received null response from sendCommand 'getStatusEx'", deviceName);
                return;
            }
            handleStatusUpdate(response);
        } catch (Exception e) {
            handleGroupError("triggering group state update", e);
        }
    }

    /**
     * Handle received commands for the group management channels.
     */
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (ipAddress.isEmpty()) {
            logger.warn("[{}] Cannot handle command - device IP is empty", deviceName);
            return;
        }

        String channelId = channelUID.getIdWithoutGroup();

        switch (channelId) {
            case CHANNEL_JOIN:
                handleJoinCommand(command);
                break;

            case CHANNEL_LEAVE:
                handleLeaveCommand();
                break;

            case CHANNEL_UNGROUP:
                handleUngroupCommand();
                break;

            case CHANNEL_KICKOUT:
                handleKickoutCommand(command);
                break;

            case CHANNEL_GROUP_VOLUME:
                handleGroupVolumeCommand(command);
                break;

            case CHANNEL_GROUP_MUTE:
                handleGroupMuteCommand(command);
                break;

            default:
                logger.debug("[{}] Unhandled channel command: {}", deviceName, channelId);
        }
    }

    private void handleJoinCommand(Command command) {
        if (command instanceof StringType) {
            String masterIp = command.toString();
            if (!masterIp.isEmpty()) {
                try {
                    JsonObject response = httpManager.sendCommand("joinGroup:" + masterIp);
                    logger.debug("[{}] Join group response: {}", deviceName, response);
                    triggerGroupStateUpdate();
                } catch (Exception e) {
                    handleGroupError("joining group", e);
                }
            }
        }
    }

    private void handleLeaveCommand() {
        try {
            JsonObject response = httpManager.sendCommand("leaveGroup");
            logger.debug("[{}] Leave group response: {}", deviceName, response);
            triggerGroupStateUpdate();
        } catch (Exception e) {
            handleGroupError("leaving group", e);
        }
    }

    private void handleUngroupCommand() {
        try {
            JsonObject response = httpManager.sendCommand("ungroup");
            logger.debug("[{}] Ungroup response: {}", deviceName, response);
            triggerGroupStateUpdate();
        } catch (Exception e) {
            handleGroupError("ungrouping", e);
        }
    }

    private void handleKickoutCommand(Command command) {
        if (command instanceof StringType) {
            String slaveIp = command.toString();
            try {
                JsonObject response = httpManager.sendCommand("kickoutSlave:" + slaveIp);
                logger.debug("[{}] Kickout response: {}", deviceName, response);
                triggerGroupStateUpdate();
            } catch (Exception e) {
                handleGroupError("kicking out slave", e);
            }
        }
    }

    private void handleGroupVolumeCommand(Command command) {
        if (command instanceof PercentType) {
            int volume = ((PercentType) command).intValue();
            try {
                @Nullable
                JsonObject status = httpManager.sendCommand("getStatusEx");
                if (status == null) {
                    logger.warn(
                            "[{}] Received null response from sendCommand 'getStatusEx' in handleGroupVolumeCommand",
                            deviceName);
                    return;
                }
                JsonObject nonNullStatus = status; // Assign to a non-null variable
                MultiroomInfo info = new MultiroomInfo(nonNullStatus);
                for (String slaveIp : info.getSlaveIPList()) {
                    try {
                        @Nullable
                        JsonObject slaveResponse = httpManager.sendCommand("setPlayerCmd:vol:" + volume);
                        if (slaveResponse == null) {
                            logger.warn(
                                    "[{}] Received null response from sendCommand 'setPlayerCmd:vol:{}' for slave {}",
                                    deviceName, volume, slaveIp);
                            continue;
                        }
                        JsonObject nonNullSlaveResponse = slaveResponse; // Assign to a non-null variable
                        logger.debug("[{}] Set volume response for {}: {}", deviceName, slaveIp, nonNullSlaveResponse);
                    } catch (Exception e) {
                        handleGroupError("setting volume for " + slaveIp, e);
                    }
                }
                @Nullable
                JsonObject masterResponse = httpManager.sendCommand("setPlayerCmd:vol:" + volume);
                if (masterResponse == null) {
                    logger.warn("[{}] Received null response from sendCommand 'setPlayerCmd:vol:{}' for master {}",
                            deviceName, volume, ipAddress);
                    return;
                }
                JsonObject nonNullMasterResponse = masterResponse; // Assign to a non-null variable
                logger.debug("[{}] Set volume response for master {}: {}", deviceName, ipAddress,
                        nonNullMasterResponse);
            } catch (Exception e) {
                handleGroupError("volume control operation", e);
            }
        }
    }

    private void handleGroupMuteCommand(Command command) {
        if (command instanceof OnOffType) {
            boolean mute = command == OnOffType.ON;
            int muteValue = mute ? 1 : 0;
            try {
                @Nullable
                JsonObject status = httpManager.sendCommand("getStatusEx");
                if (status == null) {
                    logger.warn("[{}] Received null response from sendCommand 'getStatusEx' in handleGroupMuteCommand",
                            deviceName);
                    return;
                }
                JsonObject nonNullStatus = status; // Assign to a non-null variable
                MultiroomInfo info = new MultiroomInfo(nonNullStatus);
                for (String slaveIp : info.getSlaveIPList()) {
                    try {
                        @Nullable
                        JsonObject slaveResponse = httpManager.sendCommand("setPlayerCmd:mute:" + muteValue);
                        if (slaveResponse == null) {
                            logger.warn(
                                    "[{}] Received null response from sendCommand 'setPlayerCmd:mute:{}' for slave {}",
                                    deviceName, muteValue, slaveIp);
                            continue;
                        }
                        JsonObject nonNullSlaveResponse = slaveResponse; // Assign to a non-null variable
                        logger.debug("[{}] Set mute response for {}: {}", deviceName, slaveIp, nonNullSlaveResponse);
                    } catch (Exception e) {
                        handleGroupError("setting mute for " + slaveIp, e);
                    }
                }
                @Nullable
                JsonObject masterResponse = httpManager.sendCommand("setPlayerCmd:mute:" + muteValue);
                if (masterResponse == null) {
                    logger.warn("[{}] Received null response from sendCommand 'setPlayerCmd:mute:{}' for master {}",
                            deviceName, muteValue, ipAddress);
                    return;
                }
                JsonObject nonNullMasterResponse = masterResponse; // Assign to a non-null variable
                logger.debug("[{}] Set mute response for master {}: {}", deviceName, ipAddress, nonNullMasterResponse);
            } catch (Exception e) {
                handleGroupError("mute control operation", e);
            }
        }
    }

    /**
     * Handle status update from JSON response
     */
    private void handleStatusUpdate(JsonObject json) {
        try {
            if (json.has("multiroom")) {
                JsonElement multiroomElement = json.get("multiroom");
                if (multiroomElement.isJsonObject()) {
                    JsonObject multiroom = multiroomElement.getAsJsonObject();
                    MultiroomInfo info = new MultiroomInfo(multiroom);
                    updateGroupState(info);
                }
            }
        } catch (Exception e) {
            handleGroupError("parsing status update", e);
        }
    }

    /**
     * Handle group error without redundant null check.
     */
    private void handleGroupError(String operation, Throwable error) {
        logger.warn("[{}] Error {} : {}", deviceName, operation, error.getMessage());
        if (logger.isDebugEnabled()) {
            logger.debug("[{}] Error details:", deviceName, error);
        }
    }

    private void updateGroupState(MultiroomInfo multiroomInfo) {
        try {
            logger.debug("[{}] Group state updated - Role: {}, Master IP: {}, Slaves: {}", deviceName,
                    multiroomInfo.getRole(), multiroomInfo.getMasterIP(), multiroomInfo.getSlaveIPs());
        } catch (Exception e) {
            handleGroupError("updating group state", e);
        }
    }

    public void dispose() {
        logger.debug("[{}] Disposing LinkPlayGroupManager for IP: {}", deviceName, ipAddress);
    }
}
