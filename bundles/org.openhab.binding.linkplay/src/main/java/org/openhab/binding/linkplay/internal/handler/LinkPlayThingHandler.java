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

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpManager;
import org.openhab.binding.linkplay.internal.upnp.LinkPlayUpnpManager;
import org.openhab.binding.linkplay.internal.handler.LinkPlayGroupManager;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refactored {@link LinkPlayThingHandler} to delegate responsibilities to HTTP, UPnP, and Group Managers.
 */
@NonNullByDefault
public class LinkPlayThingHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayThingHandler.class);

    private final LinkPlayHttpManager httpManager;
    private final LinkPlayUpnpManager upnpManager;
    private final LinkPlayGroupManager groupManager;

    private @Nullable ScheduledFuture<?> pollingJob;

    public LinkPlayThingHandler(Thing thing, LinkPlayHttpManager httpManager, UpnpIOService upnpIOService) {
        super(thing);
        this.httpManager = httpManager;
        this.upnpManager = new LinkPlayUpnpManager(upnpIOService, thing, scheduler);
        this.groupManager = new LinkPlayGroupManager(httpManager);
    }

    @Override
    public void initialize() {
        String ipAddress = (String) getThing().getConfiguration().get(CONFIG_IP_ADDRESS);

        if (ipAddress == null || ipAddress.isEmpty()) {
            logger.warn("IP address not configured for Thing: {}", getThing().getUID());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "IP address not configured");
            return;
        }

        httpManager.setIpAddress(ipAddress);
        String udn = getThing().getConfiguration().get(CONFIG_DEVICE_ID).toString();
        if (udn != null && !udn.isEmpty()) {
            upnpManager.register(udn);
        } else {
            logger.warn("UPnP UDN not configured for Thing: {}", getThing().getUID());
        }

        updateStatus(ThingStatus.ONLINE);
        startPolling();
    }

    @Override
    public void dispose() {
        stopPolling();
        upnpManager.unregister();
        super.dispose();
    }

    private void startPolling() {
        stopPolling();
        pollingJob = scheduler.scheduleWithFixedDelay(() -> {
            httpManager.getPlayerStatus().whenComplete((response, error) -> {
                if (error != null) {
                    logger.warn("Failed to poll player status: {}", error.getMessage());
                } else {
                    // Update channels based on response
                    logger.debug("Polling response: {}", response);
                }
            });
        }, 0, 10, TimeUnit.SECONDS);
    }

    private void stopPolling() {
        if (pollingJob != null) {
            pollingJob.cancel(true);
            pollingJob = null;
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        String channelId = channelUID.getIdWithoutGroup();
        switch (channelId) {
            case CHANNEL_CONTROL:
            case CHANNEL_VOLUME:
            case CHANNEL_MUTE:
                handlePlaybackCommand(channelUID, command);
                break;

            case CHANNEL_JOIN:
            case CHANNEL_LEAVE:
            case CHANNEL_UNGROUP:
                groupManager.handleGroupCommand(channelUID, command);
                break;

            default:
                logger.warn("[{}] Unhandled channel: {}", getThing().getUID(), channelId);
        }
    }

    private void handlePlaybackCommand(ChannelUID channelUID, Command command) {
        httpManager.sendCommand(channelUID.getId(), command.toString()).whenComplete((response, error) -> {
            if (error != null) {
                logger.warn("Failed to send playback command: {}", error.getMessage());
            } else {
                logger.debug("Playback command response: {}", response);
            }
        });
    }
}


