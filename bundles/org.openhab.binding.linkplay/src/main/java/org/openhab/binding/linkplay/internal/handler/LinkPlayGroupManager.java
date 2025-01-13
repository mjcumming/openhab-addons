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

import java.util.ArrayList;
import java.util.List;
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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The {@link LinkPlayGroupManager} handles multiroom functionality for LinkPlay devices.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayGroupManager {
    private final Logger logger = LoggerFactory.getLogger(LinkPlayGroupManager.class);
    private final LinkPlayThingHandler handler;
    private final LinkPlayHttpClient httpClient;
    private String ipAddress;

    public LinkPlayGroupManager(LinkPlayThingHandler handler, LinkPlayHttpClient httpClient, String ipAddress) {
        this.handler = handler;
        this.httpClient = httpClient;
        this.ipAddress = ipAddress;
    }

    public void initialize(String ipAddress) {
        if (!ipAddress.isEmpty()) {
            this.ipAddress = ipAddress;
            updateGroupState();
        } else {
            logger.debug("Cannot initialize group manager - IP address is empty");
        }
    }

    public void updateGroupState() {
        if (!(handler instanceof LinkPlayThingHandler)) {
            return;
        }

        if (ipAddress.isEmpty()) {
            logger.debug("Cannot update group state - device IP is empty");
            return;
        }

        httpClient.getStatusEx(ipAddress).thenAccept(status -> {
            try {
                MultiroomInfo info = new MultiroomInfo(status);
                handler.updateGroupChannels(info.getRole(), info.getMasterIP(), info.getSlaveIPs());
            } catch (Exception e) {
                logger.debug("Error parsing multiroom status: {}", e.getMessage());
                handleGroupError("parsing status", e);
            }
        }).exceptionally(e -> {
            handleGroupError("updating group state", e);
            return null;
        });
    }

    public void handleCommand(ChannelUID channelUID, Command command) {
        if (ipAddress.isEmpty()) {
            logger.debug("Cannot handle command - device IP is empty");
            return;
        }

        String channelId = channelUID.getIdWithoutGroup();

        try {
            switch (channelId) {
                case CHANNEL_MASTER_IP:
                    if (command instanceof StringType) {
                        String masterIP = command.toString();
                        if (!masterIP.isEmpty()) {
                            httpClient.joinGroup(ipAddress, masterIP).thenAccept(response -> {
                                logger.debug("Join group response: {}", response);
                                updateGroupState();
                            }).exceptionally(e -> {
                                handleGroupError("joining group", e);
                                return null;
                            });
                        }
                    }
                    break;

                case CHANNEL_JOIN:
                    if (command instanceof OnOffType) {
                        if (command == OnOffType.OFF) {
                            httpClient.leaveGroup(ipAddress).thenAccept(response -> {
                                logger.debug("Leave group response: {}", response);
                                updateGroupState();
                            }).exceptionally(e -> {
                                handleGroupError("leaving group", e);
                                return null;
                            });
                        }
                    }
                    break;

                case CHANNEL_UNGROUP:
                    if (command instanceof OnOffType && command == OnOffType.ON) {
                        httpClient.ungroup(ipAddress).thenAccept(response -> {
                            logger.debug("Ungroup response: {}", response);
                            updateGroupState();
                        }).exceptionally(e -> {
                            handleGroupError("ungrouping", e);
                            return null;
                        });
                    }
                    break;

                case CHANNEL_GROUP_VOLUME:
                    if (command instanceof PercentType) {
                        int volume = ((PercentType) command).intValue();
                        httpClient.getStatusEx(ipAddress).thenAccept(status -> {
                            MultiroomInfo info = new MultiroomInfo(status);
                            if ("master".equals(info.getRole())) {
                                httpClient.setGroupVolume(ipAddress, info.getSlaveIPs(), volume)
                                        .thenAccept(response -> {
                                            logger.debug("Set group volume response: {}", response);
                                        }).exceptionally(e -> {
                                            handleGroupError("setting group volume", e);
                                            return null;
                                        });
                            } else {
                                logger.debug("Cannot set group volume - device is not a master");
                            }
                        }).exceptionally(e -> {
                            handleGroupError("getting group status", e);
                            return null;
                        });
                    }
                    break;

                case CHANNEL_GROUP_MUTE:
                    if (command instanceof OnOffType) {
                        boolean mute = command == OnOffType.ON;
                        httpClient.getStatusEx(ipAddress).thenAccept(status -> {
                            MultiroomInfo info = new MultiroomInfo(status);
                            if ("master".equals(info.getRole())) {
                                httpClient.setGroupMute(ipAddress, info.getSlaveIPs(), mute).thenAccept(response -> {
                                    logger.debug("Set group mute response: {}", response);
                                }).exceptionally(e -> {
                                    handleGroupError("setting group mute", e);
                                    return null;
                                });
                            } else {
                                logger.debug("Cannot set group mute - device is not a master");
                            }
                        }).exceptionally(e -> {
                            handleGroupError("getting group status", e);
                            return null;
                        });
                    }
                    break;
            }
        } catch (Exception e) {
            logger.debug("Error handling command {}: {}", command, e.getMessage());
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

    public void joinGroup(String masterIp) {
        httpClient.sendCommand(ipAddress, String.format("multiroom/join?master=%s", masterIp));
    }

    public void leaveGroup() {
        httpClient.sendCommand(ipAddress, "multiroom/leave");
    }

    public void ungroup() {
        httpClient.sendCommand(ipAddress, "multiroom/ungroup");
    }

    public void kickoutSlave(String slaveIp) {
        httpClient.sendCommand(ipAddress, String.format("multiroom/kickout?slave=%s", slaveIp));
    }

    public void setGroupVolume(int volume) {
        httpClient.sendCommand(ipAddress, String.format("multiroom/vol:%d", volume));
    }

    public void setGroupMute(boolean mute) {
        httpClient.sendCommand(ipAddress, String.format("multiroom/mute:%d", mute ? 1 : 0));
    }

    public void handleStatusUpdate(JsonObject status) {
        String uuid = status.has("uuid") ? status.get("uuid").getAsString() : "";
        String hostUuid = status.has("host_uuid") ? status.get("host_uuid").getAsString() : "";

        String role = determineRole(uuid, hostUuid);
        String masterIP = determineMasterIP(status);
        String slaveIPs = determineSlaveIPs(status);

        handler.updateGroupChannels(role, masterIP, slaveIPs);
    }

    private String determineRole(String uuid, String hostUuid) {
        if (hostUuid.isEmpty()) {
            return "standalone";
        }
        return uuid.equals(hostUuid) ? "master" : "slave";
    }

    private String determineMasterIP(JsonObject status) {
        if (status.has("host_ip")) {
            return status.get("host_ip").getAsString();
        }
        return "";
    }

    private String determineSlaveIPs(JsonObject status) {
        if (!status.has("slave_list")) {
            return "";
        }

        JsonArray slaveList = status.getAsJsonArray("slave_list");
        List<String> slaveIPs = new ArrayList<>();

        for (JsonElement slave : slaveList) {
            JsonObject slaveObj = slave.getAsJsonObject();
            if (slaveObj.has("ip")) {
                slaveIPs.add(slaveObj.get("ip").getAsString());
            }
        }

        return String.join(",", slaveIPs);
    }
}
