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

    /** The IP address of the current device (the "local" device) */
    private String ipAddress = "";

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
     * Trigger an update of the group state by querying getStatusEx on THIS device.
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
                logger.warn("[{}] Received null response from 'getStatusEx'", deviceName);
                return;
            }
            // Now handle the entire JSON root with MultiroomInfo
            handleStatusUpdate(response);
        } catch (Exception e) {
            handleGroupError("triggering group state update", e);
        }
    }

    /**
     * Called from LinkPlayThingHandler or elsewhere when an openHAB command arrives
     * on a multiroom-related channel (join, leave, ungroup, etc.).
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
                logger.trace("[{}] Unhandled channel command: {}", deviceName, channelId);
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
                    logger.trace("[{}] Join group response: {}", deviceName, response);
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
            logger.trace("[{}] Leave group response: {}", deviceName, response);
            triggerGroupStateUpdate();
        } catch (Exception e) {
            handleGroupError("leaving group", e);
        }
    }

    private void handleUngroupCommand() {
        try {
            JsonObject response = httpManager.sendCommand("ungroup");
            logger.trace("[{}] Ungroup response: {}", deviceName, response);
            triggerGroupStateUpdate();
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
                    logger.trace("[{}] Kickout response: {}", deviceName, response);
                    triggerGroupStateUpdate();
                } catch (Exception e) {
                    handleGroupError("kicking out slave", e);
                }
            }
        }
    }

    /**
     * Called when user sets the groupVolume channel. We'll fetch getStatusEx
     * to see if we are master. If so, we set volume on all slaves + self.
     */
    private void handleGroupVolumeCommand(Command command) {
        if (!(command instanceof PercentType)) {
            return;
        }
        int volume = ((PercentType) command).intValue();

        try {
            @Nullable
            JsonObject status = httpManager.sendCommand("getStatusEx");
            if (status == null) {
                logger.warn("[{}] Null response from 'getStatusEx' in handleGroupVolumeCommand", deviceName);
                return;
            }
            // Build MultiroomInfo from the entire root JSON
            MultiroomInfo info = new MultiroomInfo(status);

            // If we are not master, some choose to do nothing or allow partial sets
            if (!"master".equals(info.getRole())) {
                logger.trace("[{}] Not master => skipping group volume for slaves. (role={})", deviceName,
                        info.getRole());
                // But let's still set volume on this device if desired
                setVolumeOnDevice(volume, ipAddress, "master/self");
                return;
            }

            // We are master => set volume on all slaves
            for (String slaveIp : info.getSlaveIPList()) {
                setVolumeOnDevice(volume, slaveIp, "slave");
            }
            // Also set volume on self
            setVolumeOnDevice(volume, ipAddress, "master/self");
        } catch (Exception e) {
            handleGroupError("volume control operation", e);
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
        int muteValue = mute ? 1 : 0;

        try {
            @Nullable
            JsonObject status = httpManager.sendCommand("getStatusEx");
            if (status == null) {
                logger.warn("[{}] Null response from 'getStatusEx' in handleGroupMuteCommand", deviceName);
                return;
            }
            MultiroomInfo info = new MultiroomInfo(status);

            if (!"master".equals(info.getRole())) {
                logger.trace("[{}] Not master => skipping group mute for slaves. (role={})", deviceName,
                        info.getRole());
                // Optionally still set mute on self
                setMuteOnDevice(muteValue, ipAddress, "master/self");
                return;
            }

            // We are master => set mute for all slaves
            for (String slaveIp : info.getSlaveIPList()) {
                setMuteOnDevice(muteValue, slaveIp, "slave");
            }
            // Also set mute on self
            setMuteOnDevice(muteValue, ipAddress, "master/self");
        } catch (Exception e) {
            handleGroupError("mute control operation", e);
        }
    }

    private void setVolumeOnDevice(int volume, String targetIp, String desc) {
        try {
            @Nullable
            JsonObject resp = httpManager.sendCommand("setPlayerCmd:vol:" + volume);
            if (resp == null) {
                logger.warn("[{}] Null response from 'setPlayerCmd:vol:{}' for {} {}", deviceName, volume, desc,
                        targetIp);
            } else {
                logger.trace("[{}] Set volume={} on {} ({}): {}", deviceName, volume, desc, targetIp, resp);
            }
        } catch (Exception e) {
            handleGroupError("setting volume=" + volume + " for " + targetIp, e);
        }
    }

    private void setMuteOnDevice(int muteValue, String targetIp, String desc) {
        try {
            @Nullable
            JsonObject resp = httpManager.sendCommand("setPlayerCmd:mute:" + muteValue);
            if (resp == null) {
                logger.warn("[{}] Null response from 'setPlayerCmd:mute:{}' for {} {}", deviceName, muteValue, desc,
                        targetIp);
            } else {
                logger.trace("[{}] Set mute={} on {} ({}): {}", deviceName, muteValue, desc, targetIp, resp);
            }
        } catch (Exception e) {
            handleGroupError("setting mute=" + muteValue + " for " + targetIp, e);
        }
    }

    // ------------------------------------------------------------------------
    // Handling status updates from the device
    // ------------------------------------------------------------------------
    private void handleStatusUpdate(JsonObject rootJson) {
        try {
            // Build info from the entire root so we can read 'group', 'master_uuid', etc.
            MultiroomInfo info = new MultiroomInfo(rootJson);
            updateGroupState(info);
        } catch (Exception e) {
            handleGroupError("parsing status update", e);
        }
    }

    private void updateGroupState(MultiroomInfo info) {
        logger.info("[{}] Group state updated => role={}, masterIP={}, slaves={}", deviceName, info.getRole(),
                info.getMasterIP(), info.getSlaveIPs());
        // In the future, you can do more here, e.g., update channels
    }

    private void handleGroupError(String operation, Throwable error) {
        logger.warn("[{}] Error {} : {}", deviceName, operation, error.getMessage());
        if (logger.isDebugEnabled()) {
            logger.debug("[{}] Error details:", deviceName, error);
        }
    }

    public void dispose() {
        logger.debug("[{}] Disposing LinkPlayGroupManager for IP: {}", deviceName, ipAddress);
        // nothing special yet
    }
}
