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
import org.openhab.core.thing.Thing;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LinkPlayGroupManager} handles multiroom functionality for LinkPlay devices.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayGroupManager {
    private final Logger logger = LoggerFactory.getLogger(LinkPlayGroupManager.class);
    private final Thing thing;
    private final LinkPlayHttpClient httpClient;
    private String deviceIp = "";

    public LinkPlayGroupManager(Thing thing, LinkPlayHttpClient httpClient) {
        this.thing = thing;
        this.httpClient = httpClient;
    }

    public void initialize(String ipAddress) {
        if (!ipAddress.isEmpty()) {
            this.deviceIp = ipAddress;
            updateGroupState();
        } else {
            logger.debug("Cannot initialize group manager - IP address is empty");
        }
    }

    public void updateGroupState() {
        if (!(thing.getHandler() instanceof LinkPlayThingHandler handler)) {
            return;
        }

        if (deviceIp.isEmpty()) {
            logger.debug("Cannot update group state - device IP is empty");
            return;
        }

        httpClient.getStatusEx(deviceIp).thenAccept(status -> {
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
        if (deviceIp.isEmpty()) {
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
                            httpClient.joinGroup(deviceIp, masterIP).thenAccept(response -> {
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
                            httpClient.leaveGroup(deviceIp).thenAccept(response -> {
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
                        httpClient.ungroup(deviceIp).thenAccept(response -> {
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
                        httpClient.getStatusEx(deviceIp).thenAccept(status -> {
                            MultiroomInfo info = new MultiroomInfo(status);
                            if ("master".equals(info.getRole())) {
                                httpClient.setGroupVolume(deviceIp, info.getSlaveIPs(), volume).thenAccept(response -> {
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
                        httpClient.getStatusEx(deviceIp).thenAccept(status -> {
                            MultiroomInfo info = new MultiroomInfo(status);
                            if ("master".equals(info.getRole())) {
                                httpClient.setGroupMute(deviceIp, info.getSlaveIPs(), mute).thenAccept(response -> {
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
}
