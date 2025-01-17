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
    public final void handleStateUpdate(String channelId, State state) {
        // First try to find the channel in its group
        for (String groupId : new String[] { "playback", "system", "network", "multiroom" }) {
            Channel channel = getThing().getChannel(groupId + "#" + channelId);
            if (channel != null) {
                // Found the channel in this group
                logger.trace("Updating state for channel {}/{} to {}", groupId, channelId, state);
                updateState(channel.getUID(), state);
                return;
            }
        }

        // If no grouped channel found, try updating directly (though this shouldn't happen)
        Channel channel = getThing().getChannel(channelId);
        if (channel != null) {
            logger.trace("Updating state for ungrouped channel {} to {}", channelId, state);
            updateState(channel.getUID(), state);
        } else {
            logger.debug("Channel not found: {}", channelId);
        }
    }

    /**
     * Public method for updating thing status, used by the device manager.
     */
    public final void handleStatusUpdate(ThingStatus status, @Nullable ThingStatusDetail detail,
            @Nullable String description) {
        updateStatus(status, detail != null ? detail : ThingStatusDetail.NONE, description != null ? description : "");
    }

    /**
     * Public method for updating thing status, used by the device manager.
     */
    public final void handleStatusUpdate(ThingStatus status) {
        updateStatus(status);
    }

    /**
     * Updates the UDN in the Thing configuration.
     * This is called when we discover the UDN from the device.
     */
    public final void updateUdnInConfig(String udn) {
        Configuration config = editConfiguration();
        config.put("udn", udn);
        updateConfiguration(config);
        logger.debug("Updated UDN in configuration to: {}", udn);
    }
}
