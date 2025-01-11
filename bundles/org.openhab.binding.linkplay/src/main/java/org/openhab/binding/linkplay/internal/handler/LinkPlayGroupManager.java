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
import org.openhab.binding.linkplay.internal.http.LinkPlayCommunicationException;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpClient;
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
        this.deviceIp = ipAddress;
        updateGroupState();
    }

    public void updateGroupState() {
        if (!(thing.getHandler() instanceof LinkPlayThingHandler handler)) {
            return;
        }

        httpClient.getMultiroomStatus(deviceIp).thenAccept(status -> {
            String role = status.getRole();
            String masterIP = status.getMasterIP();
            String slaveIPs = String.join(",", status.getSlaveIPs());

            handler.updateGroupChannels(role, masterIP, slaveIPs);
        }).exceptionally(e -> {
            handleGroupError("updating group state", e);
            return null;
        });
    }

    public void handleCommand(ChannelUID channelUID, Command command) {
        String channelId = channelUID.getIdWithoutGroup();

        try {
            switch (channelId) {
                case CHANNEL_MASTER_IP:
                    if (command instanceof StringType) {
                        String masterIP = command.toString();
                        joinGroup(deviceIp, masterIP);
                    }
                    break;

                case CHANNEL_SLAVE_IPS:
                    if (command instanceof StringType) {
                        String slaveIP = command.toString();
                        kickoutSlave(deviceIp, slaveIP);
                    }
                    break;

                case CHANNEL_GROUP_VOLUME:
                    if (command instanceof PercentType && thing.getHandler() instanceof LinkPlayThingHandler handler) {
                        handleGroupVolume(handler, ((PercentType) command).intValue());
                    }
                    break;

                case CHANNEL_GROUP_MUTE:
                    if (command instanceof OnOffType && thing.getHandler() instanceof LinkPlayThingHandler handler) {
                        handleGroupMute(handler, OnOffType.ON.equals(command));
                    }
                    break;

                default:
                    logger.debug("Channel {} not handled in group manager", channelId);
            }
        } catch (Exception e) {
            handleGroupError("executing group command", e);
        }
    }

    private void handleGroupVolume(LinkPlayThingHandler handler, int volume) {
        httpClient.getMultiroomStatus(deviceIp).thenAccept(status -> {
            if ("master".equals(status.getRole())) {
                httpClient.setGroupVolume(deviceIp, status.getSlaveIPs(), volume).thenRun(this::updateGroupState);
            } else {
                logger.debug("Group volume command ignored - device is not a master");
            }
        }).exceptionally(e -> {
            handleGroupError("setting group volume", e);
            return null;
        });
    }

    private void handleGroupMute(LinkPlayThingHandler handler, boolean mute) {
        httpClient.getMultiroomStatus(deviceIp).thenAccept(status -> {
            if ("master".equals(status.getRole())) {
                httpClient.setGroupMute(deviceIp, status.getSlaveIPs(), mute).thenRun(this::updateGroupState);
            } else {
                logger.debug("Group mute command ignored - device is not a master");
            }
        }).exceptionally(e -> {
            handleGroupError("setting group mute", e);
            return null;
        });
    }

    private void joinGroup(String deviceIp, String masterIP) {
        httpClient.joinGroup(deviceIp, masterIP).thenRun(this::updateGroupState).exceptionally(e -> {
            handleGroupError("joining group", e);
            return null;
        });
    }

    private void kickoutSlave(String deviceIp, String slaveIp) {
        httpClient.kickoutSlave(deviceIp, slaveIp).thenRun(this::updateGroupState).exceptionally(e -> {
            handleGroupError("kicking out slave", e);
            return null;
        });
    }

    private void handleGroupError(String operation, Throwable e) {
        String errorMessage = e.getMessage();
        logger.debug("Error {} for device {}: {}", operation, deviceIp,
                errorMessage != null ? errorMessage : "Unknown error");
        if (thing.getHandler() instanceof LinkPlayThingHandler handler) {
            handler.handleCommunicationError(
                    new LinkPlayCommunicationException(errorMessage != null ? errorMessage : "Unknown error"));
        }
    }
}
