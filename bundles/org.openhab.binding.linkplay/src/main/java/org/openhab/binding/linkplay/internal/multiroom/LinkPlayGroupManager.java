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

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.linkplay.internal.LinkPlayDeviceManager;
import org.openhab.binding.linkplay.internal.model.LinkPlayMultiroomInfo;
import org.openhab.binding.linkplay.internal.transport.http.LinkPlayHttpManager;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public LinkPlayGroupManager(LinkPlayDeviceManager deviceManager) {
        this.deviceManager = deviceManager;
        this.httpManager = deviceManager.getHttpManager();
    }

    /**
     * Handle multiroom-related commands from DeviceManager
     */
    public void handleCommand(String channelId, Command command) {
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
                logger.trace("Unhandled channel command: {}", channelId);
        }
    }

    // ------------------------------------------------------------------------
    // Multiroom Commands
    // ------------------------------------------------------------------------
    private void handleJoinCommand(Command command) {
        if (command instanceof StringType) {
            String masterIp = command.toString().trim();
            if (!masterIp.isEmpty()) {
                try {
                    JsonObject response = httpManager.sendCommand("joinGroup:" + masterIp);
                    logger.trace("Join group response: {}", response);
                    if (response != null) {
                        LinkPlayMultiroomInfo info = new LinkPlayMultiroomInfo(response);
                        handleJoinGroupResponse(info.getSlaveIPList());
                    }
                } catch (Exception e) {
                    handleGroupError("joining group", e);
                }
            }
        }
    }

    private void handleLeaveCommand() {
        try {
            JsonObject response = httpManager.sendCommand("leaveGroup");
            logger.trace("Leave group response: {}", response);
            if (response != null) {
                LinkPlayMultiroomInfo info = new LinkPlayMultiroomInfo(response);
                handleLeaveGroupResponse(info.getSlaveIPList());
            }
        } catch (Exception e) {
            handleGroupError("leaving group", e);
        }
    }

    private void handleUngroupCommand() {
        try {
            JsonObject response = httpManager.sendCommand("ungroup");
            logger.trace("Ungroup response: {}", response);
        } catch (Exception e) {
            handleGroupError("ungrouping", e);
        }
    }

    private void handleKickoutCommand(Command command) {
        if (command instanceof StringType) {
            String slaveIp = command.toString().trim();
            if (!slaveIp.isEmpty()) {
                try {
                    JsonObject response = httpManager.sendCommand("kickoutSlave:" + slaveIp);
                    logger.trace("Kickout response: {}", response);
                } catch (Exception e) {
                    handleGroupError("kicking out slave", e);
                }
            }
        }
    }

    /**
     * Called when user sets the groupVolume channel, we set volume on all slaves + self.
     */
    private void handleGroupVolumeCommand(Command command) {
        if (!(command instanceof PercentType)) {
            return;
        }
        int volume = ((PercentType) command).intValue();

        // Only proceed if we are the master
        if (!"master".equals(deviceManager.getDeviceState().getRole())) {
            logger.debug("Ignoring group volume command - not master device");
            return;
        }

        // Set volume on master (self)
        sendVolumeCommand(volume);

        // Set volume on all slaves
        String slaveIps = deviceManager.getDeviceState().getSlaveIPs();
        if (!slaveIps.isEmpty()) {
            for (String slaveIp : slaveIps.split(",")) {
                try {
                    JsonObject resp = httpManager.sendCommand("setPlayerCmd:slavevol:" + slaveIp + ":" + volume);
                    if (resp == null) {
                        logger.warn("Null response setting slave volume={} for {}", volume, slaveIp);
                    } else {
                        logger.trace("Set slave volume={} on {}: {}", volume, slaveIp, resp);
                    }
                } catch (Exception e) {
                    handleGroupError("setting slave volume=" + volume + " for " + slaveIp, e);
                }
            }
        }
    }

    /**
     * Called when user sets the groupMute channel. We'll see if we are master,
     * then set mute=on/off for all slaves + self.
     */
    private void handleGroupMuteCommand(Command command) {
        if (!(command instanceof OnOffType)) {
            return;
        }
        boolean mute = (command == OnOffType.ON);

        // Only proceed if we are the master
        if (!"master".equals(deviceManager.getDeviceState().getRole())) {
            logger.debug("Ignoring group mute command - not master device");
            return;
        }

        // Set mute on master (self)
        sendMuteCommand(mute);

        // Set mute on all slaves
        String slaveIps = deviceManager.getDeviceState().getSlaveIPs();
        if (!slaveIps.isEmpty()) {
            for (String slaveIp : slaveIps.split(",")) {
                try {
                    JsonObject resp = httpManager
                            .sendCommand("setPlayerCmd:slavemute:" + slaveIp + ":" + (mute ? 1 : 0));
                    if (resp == null) {
                        logger.warn("Null response setting slave mute={} for {}", mute, slaveIp);
                    } else {
                        logger.trace("Set slave mute={} on {}: {}", mute, slaveIp, resp);
                    }
                } catch (Exception e) {
                    handleGroupError("setting slave mute=" + mute + " for " + slaveIp, e);
                }
            }
        }
    }

    private void sendVolumeCommand(int volume) {
        try {
            JsonObject resp = httpManager.sendCommand("setPlayerCmd:vol:" + volume);
            if (resp == null) {
                logger.warn("Null response from 'setPlayerCmd:vol:{}' for {}", volume,
                        deviceManager.getDeviceState().getIpAddress());
            } else {
                logger.trace("Set volume={} on {}", volume, deviceManager.getDeviceState().getIpAddress());
            }
        } catch (Exception e) {
            handleGroupError("setting volume=" + volume + " for " + deviceManager.getDeviceState().getIpAddress(), e);
        }
    }

    private void sendMuteCommand(boolean mute) {
        try {
            JsonObject resp = httpManager.sendCommand("setPlayerCmd:mute:" + (mute ? 1 : 0));
            if (resp == null) {
                logger.warn("Null response from 'setPlayerCmd:mute:{}' for {}", mute,
                        deviceManager.getDeviceState().getIpAddress());
            } else {
                logger.trace("Set mute={} on {}", mute, deviceManager.getDeviceState().getIpAddress());
            }
        } catch (Exception e) {
            handleGroupError("setting mute=" + mute + " for " + deviceManager.getDeviceState().getIpAddress(), e);
        }
    }

    // ------------------------------------------------------------------------
    // Handling status updates from the device
    // ------------------------------------------------------------------------
    public void handleStatusUpdate(JsonObject rootJson) {
        try {
            LinkPlayMultiroomInfo info = new LinkPlayMultiroomInfo(rootJson);

            // Update channels only
            deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_ROLE, new StringType(info.getRole()));
            deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_MASTER_IP, new StringType(info.getMasterIP()));
            deviceManager.updateState(GROUP_MULTIROOM + "#" + CHANNEL_SLAVE_IPS, new StringType(info.getSlaveIPs()));

            logger.debug("Updated multiroom status: role={}, masterIP={}, slaves={}", info.getRole(),
                    info.getMasterIP(), info.getSlaveIPs());
        } catch (Exception e) {
            handleGroupError("parsing status update", e);
        }
    }

    private void handleGroupError(String operation, Throwable error) {
        logger.warn("Error {} : {}", operation, error.getMessage());
        if (logger.isDebugEnabled()) {
            logger.debug("Error details:", error);
        }
    }

    public void dispose() {
        logger.debug("Disposing LinkPlayGroupManager");
        // nothing special yet
    }

    private void handleJoinGroupResponse(List<String> slaveIps) {
        if (!slaveIps.isEmpty()) {
            logger.debug("[{}] Successfully joined group with slaves: {}",
                    deviceManager.getDeviceState().getDeviceName(), String.join(",", slaveIps));
        }
    }

    private void handleLeaveGroupResponse(List<String> slaveIps) {
        if (!slaveIps.isEmpty()) {
            logger.debug("[{}] Successfully left group with slaves: {}", deviceManager.getDeviceState().getDeviceName(),
                    String.join(",", slaveIps));
        }
    }
}
