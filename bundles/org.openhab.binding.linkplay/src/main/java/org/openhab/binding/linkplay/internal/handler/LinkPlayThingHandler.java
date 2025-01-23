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

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.LinkPlayDeviceManager;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.binding.linkplay.internal.transport.http.LinkPlayHttpClient;
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
 * Improvements:
 * - Early polling with minimal configuration to avoid delays.
 * - Decoupled UPnP initialization from startup for better performance.
 * - Enhanced logging for better visibility into startup timings.
 * 
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayThingHandler extends BaseThingHandler {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayThingHandler.class);

    private final UpnpIOService upnpIOService;
    private final LinkPlayHttpClient httpClient;
    private LinkPlayConfiguration config;

    private @Nullable LinkPlayDeviceManager deviceManager;

    public LinkPlayThingHandler(Thing thing, LinkPlayHttpClient httpClient, UpnpIOService upnpIOService,
            LinkPlayConfiguration config) {
        super(thing);
        this.upnpIOService = upnpIOService;
        this.httpClient = httpClient;
        this.config = config;
    }

    @Override
    public void initialize() {
        logger.debug("[{}] Initializing handler...", config.getDeviceName());

        // Get configuration
        config = getConfigAs(LinkPlayConfiguration.class);

        // Try to restore configuration from properties if needed
        if (!config.isValid()) {
            Map<String, String> properties = thing.getProperties();
            Configuration configuration = editConfiguration();

            if (properties.containsKey(PROPERTY_IP)) {
                configuration.put(CONFIG_IP_ADDRESS, properties.get(PROPERTY_IP));
            }
            if (properties.containsKey(PROPERTY_UDN)) {
                configuration.put(CONFIG_UDN, properties.get(PROPERTY_UDN));
            }

            updateConfiguration(configuration);
            config = getConfigAs(LinkPlayConfiguration.class);
        }

        // Validate configuration
        if (!config.isValid()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Invalid configuration - IP address required");
            return;
        }

        // Set initial status to UNKNOWN while we try to connect
        updateStatus(ThingStatus.UNKNOWN);

        // Initialize device manager with both HTTP and UPnP support
        deviceManager = new LinkPlayDeviceManager(this, config, httpClient, upnpIOService);

        // Start HTTP polling immediately
        startPolling();

        // Initialize UPnP and fetch metadata asynchronously
        initializeAsync();
    }

    /**
     * Starts polling for device and player status immediately with minimal configuration.
     */
    private void startPolling() {
        LinkPlayDeviceManager manager = deviceManager;
        if (manager != null) {
            try {
                manager.initialize();
                logger.debug("[{}] Started HTTP polling for device", config.getDeviceName());
            } catch (Exception e) {
                logger.warn("[{}] Failed to start polling: {}", config.getDeviceName(), e.getMessage());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Error starting polling: " + e.getMessage());
            }
        }
    }

    private void initializeAsync() {
        LinkPlayDeviceManager manager = deviceManager;
        if (manager != null) {
            scheduler.execute(() -> {
                try {
                    // Initialize additional device features
                    manager.initializeAdditionalFeatures();

                    // Mark Thing as online once fully initialized
                    updateStatus(ThingStatus.ONLINE);
                    logger.debug("[{}] Fully initialized", config.getDeviceName());
                } catch (Exception e) {
                    logger.error("[{}] Asynchronous initialization failed: {}", config.getDeviceName(), e.getMessage());
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
                }
            });
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
        Channel channel = getThing().getChannel(channelId);
        if (channel != null) {
            logger.trace("Updating state for channel {} to {}", channelId, state);
            updateState(channel.getUID(), state);
            return;
        }

        if (!channelId.contains("#")) {
            for (String groupId : new String[] { "playback", "system", "network", "multiroom" }) {
                String fullChannelId = groupId + "#" + channelId;
                channel = getThing().getChannel(fullChannelId);
                if (channel != null) {
                    logger.trace("Updating state for channel {}/{} to {}", groupId, channelId, state);
                    updateState(channel.getUID(), state);
                    return;
                }
            }
            logger.debug("Channel not found in any group: {}", channelId);
        } else {
            logger.debug("Channel not found with group prefix: {}", channelId);
        }
    }

    public final void handleStatusUpdate(ThingStatus status, @Nullable ThingStatusDetail detail,
            @Nullable String description) {
        updateStatus(status, detail != null ? detail : ThingStatusDetail.NONE, description != null ? description : "");
    }

    public final void handleStatusUpdate(ThingStatus status) {
        updateStatus(status);
    }

    public final void updateUdnInConfig(String udn) {
        Configuration config = editConfiguration();
        config.put("udn", udn);
        updateConfiguration(config);
        logger.debug("Updated UDN in configuration to: {}", udn);
    }
}
