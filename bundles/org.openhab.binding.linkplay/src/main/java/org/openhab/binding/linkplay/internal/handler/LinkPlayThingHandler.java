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

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpClient;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.thing.Channel;
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
 * The {@link LinkPlayThingHandler} is responsible for handling commands and status updates for LinkPlay devices.
 * It manages the lifecycle of a LinkPlay device and integrates with the Device Manager.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayThingHandler extends BaseThingHandler {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayThingHandler.class);

    // A dedicated scheduler for device tasks (HTTP, etc.)
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private final UpnpIOService upnpIOService;
    private final LinkPlayHttpClient httpClient;
    private final LinkPlayConfiguration config; // The validated config passed from factory

    private @Nullable LinkPlayDeviceManager deviceManager;

    /**
     * Updated constructor that receives an already validated LinkPlayConfiguration.
     */
    public LinkPlayThingHandler(Thing thing, UpnpIOService upnpIOService, LinkPlayHttpClient httpClient,
            LinkPlayConfiguration config) {
        super(thing);
        this.upnpIOService = upnpIOService;
        this.httpClient = httpClient;
        this.config = config;
    }

    @Override
    public void initialize() {
        logger.info("Initializing LinkPlayThingHandler for Thing: {}", getThing().getUID());

        try {
            LinkPlayDeviceManager manager = new LinkPlayDeviceManager(this, config, httpClient, upnpIOService);
            deviceManager = manager;
            manager.initialize();
            updateStatus(ThingStatus.ONLINE);
        } catch (Exception e) {
            logger.error("Failed to initialize LinkPlay device: {}", e.getMessage(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Failed to initialize: " + e.getMessage());
        }
    }

    @Override
    public void dispose() {
        logger.debug("Disposing LinkPlayThingHandler for Thing: {}", getThing().getUID());

        LinkPlayDeviceManager manager = deviceManager;
        if (manager != null) {
            manager.dispose();
            deviceManager = null;
        }

        super.dispose();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.trace("Received command: {} for channel: {}", command, channelUID.getIdWithoutGroup());

        final LinkPlayDeviceManager manager = deviceManager;
        if (manager != null) {
            manager.handleCommand(channelUID.getIdWithoutGroup(), command);
        } else {
            logger.warn("Cannot handle command - device manager not initialized");
        }
    }

    /**
     * Public method for updating channel state, used by the device manager.
     */
    public void handleStateUpdate(String channelId, State state) {
        // Check if the channel is part of a group
        Channel channel = thing.getChannel(channelId);
        if (channel != null && channel.getUID().getGroupId() != null) {
            String groupId = channel.getUID().getGroupId();
            // Log for debugging
            logger.trace("Updating state for group {} channel {}", groupId, channelId);
            // Update the individual channel
            updateState(channelId, state);
        } else {
            // Update the individual channel
            logger.trace("Updating state for channel {}", channelId);
            updateState(channelId, state);
        }
    }

    /**
     * Public method for updating thing status, used by the device manager.
     */
    public void handleStatusUpdate(ThingStatus status, @Nullable ThingStatusDetail detail,
            @Nullable String description) {
        updateStatus(status, detail != null ? detail : ThingStatusDetail.NONE, description != null ? description : "");
    }

    /**
     * Public method for updating thing status, used by the device manager.
     */
    public void handleStatusUpdate(ThingStatus status) {
        updateStatus(status);
    }

    /**
     * Updates the UDN in the Thing configuration.
     * This is called when we discover the UDN from the device.
     */
    public void updateUdnInConfig(String udn) {
        Configuration config = editConfiguration();
        config.put("udn", udn);
        updateConfiguration(config);
        logger.debug("Updated UDN in configuration to: {}", udn);
    }
}
