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

import java.util.concurrent.CompletionException;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.linkplay.internal.http.LinkPlayCommunicationException;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpClient;
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
 * Refactored {@link LinkPlayGroupManager} to handle multiroom functionality more robustly.
 * This includes enhancements for role detection, command synchronization, and state updates.
 *
 * Author: Refactor Contribution
 */
@NonNullByDefault
public class LinkPlayGroupManager {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayGroupManager.class);
    private final LinkPlayHttpClient httpClient;
    private String ipAddress;

    public LinkPlayGroupManager(LinkPlayHttpClient httpClient, String ipAddress) {
        this.httpClient = httpClient;
        this.ipAddress = ipAddress;
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

        httpClient.getStatusEx(ipAddress).thenAccept(this::handleStatusUpdate).exceptionally(e -> {
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
                httpClient.joinGroup(ipAddress, masterIp).thenAccept(response -> {
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
        httpClient.leaveGroup(ipAddress).thenAccept(response -> {
            logger.debug("Leave group response: {}", response);
            triggerGroupStateUpdate();
        }).exceptionally(e -> {
            handleGroupError("leaving group", e);
            return null;
        });
    }

    private void handleUngroupCommand() {
        httpClient.ungroup(ipAddress).thenAccept(response -> {
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
            httpClient.kickoutSlave(ipAddress, slaveIp).thenAccept(response -> {
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
            httpClient.getStatusEx(ipAddress).thenAccept(status -> {
                MultiroomInfo info = new MultiroomInfo(status);
                info.getSlaveIPList().forEach(slaveIp -> {
                    httpClient.sendCommand(slaveIp, String.format("setPlayerCmd:vol:%d", volume)).thenAccept(response -> {
                        logger.debug("Set volume response for {}: {}", slaveIp, response);
                    }).exceptionally(e -> {
                        handleGroupError("setting volume for " + slaveIp, e);
                        return null;
                    });
                });
                httpClient.sendCommand(ipAddress, String.format("setPlayerCmd:vol:%d", volume)).thenAccept(response -> {
                    logger.debug("Set volume response for master {}: {}", ipAddress, response);
                }).exceptionally(e -> {
                    handleGroupError("setting volume for master", e);
                    return null;
                });
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
            httpClient.getStatusEx(ipAddress).thenAccept(status -> {
                MultiroomInfo info = new MultiroomInfo(status);
                info.getSlaveIPList().forEach(slaveIp -> {
                    httpClient.sendCommand(slaveIp, String.format("setPlayerCmd:mute:%d", muteValue)).thenAccept(response -> {
                        logger.debug("Set mute response for {}: {}", slaveIp, response);
                    }).exceptionally(e -> {
                        handleGroupError("setting mute for " + slaveIp, e);
                        return null;
                    });
                });
                httpClient.sendCommand(ipAddress, String.format("setPlayerCmd:mute:%d", muteValue)).thenAccept(response -> {
                    logger.debug("Set mute response for master {}: {}", ipAddress, response);
                }).exceptionally(e -> {
                    handleGroupError("setting mute for master", e);
                    return null;
                });
            }).exceptionally(e -> {
                handleGroupError("retrieving group state for mute", e);
                return null;
            });
        }
    }

    private void handleStatusUpdate(JsonObject status) {
        try {
            MultiroomInfo info = new MultiroomInfo(status);
            logger.debug("Updated group state: role={}, masterIP={}, slaveIPs={}",
                    info.getRole(), info.getMasterIP(), info.getSlaveIPs());
            // Implement further updates to the handler or system as needed.
        } catch (Exception e) {
            logger.warn("Failed to process group state update: {}", e.getMessage());
        }
    }

    private void handleGroupError(String operation, Throwable e) {
        Throwable cause = e instanceof CompletionException ? e.getCause() : e;
        String message = cause != null ? cause.getMessage() : "Unknown error";
        if (cause instanceof LinkPlayCommunicationException) {
            logger.debug("Communication error while {}: {}", operation, message);
        } else {
            logger.warn("Error while {}: {}", operation, message);
        }
    }

    public void dispose() {
        logger.debug("Disposing LinkPlayGroupManager for IP: {}", ipAddress);
    }
}
