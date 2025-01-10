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
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpClient;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LinkPlayThingHandler} is responsible for managing a LinkPlay device.
 * It uses sub-managers for metadata, group handling, and HTTP communication.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayThingHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayThingHandler.class);

    private final LinkPlayHttpClient httpClient;
    private final LinkPlayGroupManager groupManager;
    private final LinkPlayMetadataManager metadataManager;
    @SuppressWarnings("unused")
    private final UpnpIOService upnpIOService;

    private @Nullable ScheduledFuture<?> pollingJob;
    private String deviceName = "";
    private int pollingInterval = 10; // Default 10 seconds

    public LinkPlayThingHandler(Thing thing, LinkPlayHttpClient httpClient, UpnpIOService upnpIOService) {
        super(thing);
        this.httpClient = httpClient;
        this.upnpIOService = upnpIOService;
        this.groupManager = new LinkPlayGroupManager(httpClient);
        this.metadataManager = new LinkPlayMetadataManager(httpClient);
    }

    @Override
    public void initialize() {
        LinkPlayConfiguration config = getConfigAs(LinkPlayConfiguration.class);

        if (!config.isValid()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Invalid configuration: IP address must be in format xxx.xxx.xxx.xxx");
            return;
        }

        this.deviceName = config.deviceName;
        this.pollingInterval = config.pollingInterval;

        // Initialize the HTTP client with the IP address
        httpClient.setIpAddress(config.ipAddress);

        logger.debug("Initializing LinkPlay device: IP = {}, Name = {}, Polling Interval = {}", config.ipAddress,
                deviceName, pollingInterval);

        updateStatus(ThingStatus.UNKNOWN);
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
            try {
                boolean metadataSuccess = metadataManager.fetchAndUpdateMetadata(getThing());
                boolean groupSuccess = groupManager.updateGroupState(getThing());

                if (metadataSuccess || groupSuccess) {
                    if (thing.getStatus() != ThingStatus.ONLINE) {
                        updateStatus(ThingStatus.ONLINE);
                    }
                } else {
                    if (thing.getStatus() == ThingStatus.ONLINE) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                "Failed to communicate with device");
                    }
                }
            } catch (Exception e) {
                logger.debug("Error during polling: {}", e.getMessage());
                if (thing.getStatus() == ThingStatus.ONLINE) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
                }
            }
        }, 0, pollingInterval, TimeUnit.SECONDS);
    }

    private void stopPolling() {
        ScheduledFuture<?> job = pollingJob;
        if (job != null) {
            job.cancel(true);
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

    private void handlePlaybackCommand(ChannelUID channelUID, Command command) {
        String channelId = channelUID.getId();
        try {
            switch (channelId) {
                case CHANNEL_VOLUME:
                case CHANNEL_MUTE:
                case CHANNEL_CONTROL:
                    httpClient.sendCommand(channelId, command).whenComplete((response, error) -> {
                        if (error != null) {
                            logger.warn("Failed to send playback command: {}", error.getMessage());
                        } else {
                            logger.debug("Playback command response: {}", response);
                        }
                    });
                    break;
                default:
                    logger.warn("Unhandled playback channel: {}", channelId);
            }
        } catch (Exception e) {
            logger.warn("Error handling playback command for channel {}: {}", channelId, e.getMessage());
        }
    }

    public void updateThingState(ChannelUID channelUID, State state) {
        updateState(channelUID, state);
    }
}
