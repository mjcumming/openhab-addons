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
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpClient;
import org.openhab.core.thing.*;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LinkPlayThingHandler} is responsible for managing a LinkPlay device.
 * It uses sub-managers for metadata, group handling, and HTTP communication.
 *
 * @author Michael Cumming
 */
@NonNullByDefault
public class LinkPlayThingHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayThingHandler.class);

    private final LinkPlayHttpClient httpClient;
    private final LinkPlayGroupManager groupManager;
    private final LinkPlayMetadataManager metadataManager;

    private @Nullable ScheduledFuture<?> pollingJob;

    public LinkPlayThingHandler(Thing thing, LinkPlayHttpClient httpClient) {
        super(thing);
        this.httpClient = httpClient;
        this.groupManager = new LinkPlayGroupManager(httpClient);
        this.metadataManager = new LinkPlayMetadataManager(httpClient);
    }

    @Override
    public void initialize() {
        updateStatus(ThingStatus.UNKNOWN);

        LinkPlayConfiguration config = getConfigAs(LinkPlayConfiguration.class);

        if (config.ipAddress.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "IP address is not configured.");
            return;
        }

        this.deviceName = config.deviceName;
        this.pollingInterval = Math.max(config.pollingInterval, LinkPlayConfiguration.MIN_REFRESH_INTERVAL);

        logger.debug("Initializing LinkPlay device: IP = {}, Name = {}, Polling Interval = {}", config.ipAddress,
                deviceName, pollingInterval);

        startPolling();
    }

    @Override
    public void dispose() {
        stopPolling();
        super.dispose();
    }

    private void startPolling() {
        stopPolling();
        pollingJob = scheduler.scheduleWithFixedDelay(() -> {
            metadataManager.fetchAndUpdateMetadata(getThing());
            groupManager.updateGroupState(getThing());
        }, 0, 10, TimeUnit.SECONDS);
    }

    private void stopPolling() {
        if (pollingJob != null) {
            pollingJob.cancel(true);
            pollingJob = null;
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, org.openhab.core.types.Command command) {
        switch (channelUID.getId()) {
            case CHANNEL_VOLUME:
            case CHANNEL_MUTE:
            case CHANNEL_CONTROL:
                handlePlaybackCommand(channelUID, command);
                break;

            case CHANNEL_GROUP_JOIN:
            case CHANNEL_GROUP_LEAVE:
            case CHANNEL_GROUP_UNGROUP:
                groupManager.handleGroupCommand(channelUID, command);
                break;

            default:
                logger.warn("Unhandled channel: {}", channelUID.getId());
        }
    }

    private void handlePlaybackCommand(ChannelUID channelUID, org.openhab.core.types.Command command) {
        // Delegate to HTTP client for basic commands like volume, mute, etc.
        httpClient.sendCommand(channelUID.getId(), command).whenComplete((response, error) -> {
            if (error != null) {
                logger.warn("Failed to send playback command: {}", error.getMessage());
            } else {
                logger.debug("Playback command response: {}", response);
            }
        });
    }
}
