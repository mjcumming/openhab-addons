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
import com.google.gson.JsonParser;

/**
 * The {@link LinkPlayGroupManager} handles multiroom functionality for LinkPlay devices.
 * This includes role detection, command synchronization, and state updates.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayGroupManager {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayGroupManager.class);
    private @NonNullByDefault({}) String ipAddress;
    private final LinkPlayHttpManager httpManager;

    public LinkPlayGroupManager(LinkPlayHttpManager httpManager) {
        this.httpManager = httpManager;
    }

    public void initialize(String ipAddress) {
        if (!ipAddress.isEmpty()) {
            this.ipAddress = ipAddress;
            triggerGroupStateUpdate();
        } else {
            logger.warn("Cannot initialize group manager - IP address is empty");
        }
    }

    /**
     * Trigger updates for the group state by querying the current device's status.
     */
    public void triggerGroupStateUpdate() {
        if (ipAddress.isEmpty()) {
            logger.warn("Cannot update group state - IP address is empty");
            return;
        }

        httpManager.sendCommandWithRetry(ipAddress, "getStatusEx").thenAccept(this::handleStatusUpdate)
                .exceptionally(e -> {
                    handleGroupError("triggering group state update", e);
                    return null;
                });
    }

    /**
     * Handle received commands for the group management channels.
     */
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (ipAddress.isEmpty()) {
            logger.warn("Cannot handle command - device IP is empty");
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
                logger.debug("Unhandled channel command: {}", channelId);
        }
    }

    private void handleJoinCommand(Command command) {
        if (command instanceof StringType) {
            String masterIp = command.toString();
            if (!masterIp.isEmpty()) {
                httpManager.sendCommandWithRetry(ipAddress, "joinGroup:" + masterIp).thenAccept(response -> {
                    logger.debug("Join group response: {}", response);
                    triggerGroupStateUpdate();
                }).exceptionally(e -> {
                    handleGroupError("joining group", e);
                    return null;
                });
            }
        }
    }

    private void handleLeaveCommand() {
        httpManager.sendCommandWithRetry(ipAddress, "leaveGroup").thenAccept(response -> {
            logger.debug("Leave group response: {}", response);
            triggerGroupStateUpdate();
        }).exceptionally(e -> {
            handleGroupError("leaving group", e);
            return null;
        });
    }

    private void handleUngroupCommand() {
        httpManager.sendCommandWithRetry(ipAddress, "ungroup").thenAccept(response -> {
            logger.debug("Ungroup response: {}", response);
            triggerGroupStateUpdate();
        }).exceptionally(e -> {
            handleGroupError("ungrouping", e);
            return null;
        });
    }

    private void handleKickoutCommand(Command command) {
        if (command instanceof StringType) {
            String slaveIp = command.toString();
            httpManager.sendCommandWithRetry(ipAddress, "kickoutSlave:" + slaveIp).thenAccept(response -> {
                logger.debug("Kickout response: {}", response);
                triggerGroupStateUpdate();
            }).exceptionally(e -> {
                handleGroupError("kicking out slave", e);
                return null;
            });
        }
    }

    private void handleGroupVolumeCommand(Command command) {
        if (command instanceof PercentType) {
            int volume = ((PercentType) command).intValue();
            httpManager.sendCommandWithRetry(ipAddress, "getStatusEx").thenAccept(response -> {
                try {
                    JsonObject status = JsonParser.parseString(response).getAsJsonObject();
                    MultiroomInfo info = new MultiroomInfo(status);
                    info.getSlaveIPList().forEach(slaveIp -> {
                        httpManager.sendCommandWithRetry(slaveIp, "setPlayerCmd:vol:" + volume).thenAccept(r -> {
                            logger.debug("Set volume response for {}: {}", slaveIp, r);
                        }).exceptionally(e -> {
                            handleGroupError("setting volume for " + slaveIp, e);
                            return null;
                        });
                    });
                    httpManager.sendCommandWithRetry(ipAddress, "setPlayerCmd:vol:" + volume).thenAccept(r -> {
                        logger.debug("Set volume response for master {}: {}", ipAddress, r);
                    }).exceptionally(e -> {
                        handleGroupError("setting volume for master", e);
                        return null;
                    });
                } catch (Exception e) {
                    handleGroupError("parsing status for volume control", e);
                }
            }).exceptionally(e -> {
                handleGroupError("retrieving group state for volume", e);
                return null;
            });
        }
    }

    private void handleGroupMuteCommand(Command command) {
        if (command instanceof OnOffType) {
            boolean mute = command == OnOffType.ON;
            int muteValue = mute ? 1 : 0;
            httpManager.sendCommandWithRetry(ipAddress, "getStatusEx").thenAccept(response -> {
                try {
                    JsonObject status = JsonParser.parseString(response).getAsJsonObject();
                    MultiroomInfo info = new MultiroomInfo(status);
                    info.getSlaveIPList().forEach(slaveIp -> {
                        httpManager.sendCommandWithRetry(slaveIp, "setPlayerCmd:mute:" + muteValue).thenAccept(r -> {
                            logger.debug("Set mute response for {}: {}", slaveIp, r);
                        }).exceptionally(e -> {
                            handleGroupError("setting mute for " + slaveIp, e);
                            return null;
                        });
                    });
                    httpManager.sendCommandWithRetry(ipAddress, "setPlayerCmd:mute:" + muteValue).thenAccept(r -> {
                        logger.debug("Set mute response for master {}: {}", ipAddress, r);
                    }).exceptionally(e -> {
                        handleGroupError("setting mute for master", e);
                        return null;
                    });
                } catch (Exception e) {
                    handleGroupError("parsing status for mute control", e);
                }
            }).exceptionally(e -> {
                handleGroupError("retrieving group state for mute", e);
                return null;
            });
        }
    }

    private void handleStatusUpdate(String response) {
        try {
            JsonObject status = JsonParser.parseString(response).getAsJsonObject();
            updateGroupState(status);
        } catch (Exception e) {
            handleGroupError("parsing status update", e);
        }
    }

    private void handleGroupError(String operation, Throwable error) {
        logger.warn("[{}] Error {} : {}", ipAddress, operation, error.getMessage());
        if (logger.isDebugEnabled()) {
            logger.debug("[{}] Error details:", ipAddress, error);
        }
    }

    private void updateGroupState(JsonObject status) {
        try {
            MultiroomInfo multiroomInfo = new MultiroomInfo(status);

            logger.debug("[{}] Group state updated - Role: {}, Master IP: {}, Slaves: {}", ipAddress,
                    multiroomInfo.getRole(), multiroomInfo.getMasterIP(), multiroomInfo.getSlaveIPs());

        } catch (Exception e) {
            handleGroupError("updating group state", e);
        }
    }

    public void dispose() {
        logger.debug("Disposing LinkPlayGroupManager for IP: {}", ipAddress);
    }
}
