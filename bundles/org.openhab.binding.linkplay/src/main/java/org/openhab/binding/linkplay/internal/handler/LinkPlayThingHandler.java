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

import static org.openhab.binding.linkplay.internal.BindingConstants.*;

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.DeviceManager;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.binding.linkplay.internal.transport.http.LinkPlayHttpClient;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LinkPlayThingHandler} is responsible for handling commands and status updates for LinkPlay devices.
 * It serves as the primary interface between OpenHAB's core and LinkPlay devices, managing:
 * <ul>
 * <li>Device lifecycle (initialization, disposal)</li>
 * <li>Command handling for all channels</li>
 * <li>Status updates and state management</li>
 * <li>Configuration validation and updates</li>
 * </ul>
 * 
 * This handler delegates most device-specific operations to the {@link DeviceManager} which
 * coordinates HTTP, UPnP, and multiroom functionality.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayThingHandler extends BaseThingHandler {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayThingHandler.class);

    /** UPnP service for device discovery and events */
    private final UpnpIOService upnpIOService;

    /** HTTP client for device communication */
    private final LinkPlayHttpClient httpClient;

    /** Registry for looking up other LinkPlay things (used for multiroom) */
    private final ThingRegistry thingRegistry;

    /** Device configuration parameters */
    private LinkPlayConfiguration config;

    /** Central coordinator for device operations */
    private @Nullable DeviceManager deviceManager;

    /**
     * Creates a new instance of the LinkPlay thing handler.
     *
     * @param thing The Thing object representing the LinkPlay device
     * @param httpClient Client for making HTTP requests to the device
     * @param upnpIOService Service for UPnP communication
     * @param config Initial configuration parameters
     * @param thingRegistry Registry for looking up other Things (used for multiroom)
     */
    public LinkPlayThingHandler(Thing thing, LinkPlayHttpClient httpClient, UpnpIOService upnpIOService,
            LinkPlayConfiguration config, ThingRegistry thingRegistry) {
        super(thing);
        this.httpClient = httpClient;
        this.upnpIOService = upnpIOService;
        this.config = config;
        this.thingRegistry = thingRegistry;
    }

    /**
     * Initializes the handler through multiple phases:
     * 1. Validates and potentially restores configuration
     * 2. Starts immediate HTTP polling
     * 3. Triggers async initialization for UPnP and metadata
     * 
     * The initialization process is designed to bring up critical functionality quickly
     * while deferring non-essential setup to async operations.
     */
    @Override
    public void initialize() {
        logger.debug("[{}] Initializing handler...", config.getDeviceName());

        // Get configuration
        config = LinkPlayConfiguration.fromConfiguration(getConfig());

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
            config = LinkPlayConfiguration.fromConfiguration(getConfig());
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
        deviceManager = new DeviceManager(this, config, httpClient, upnpIOService, thingRegistry, scheduler);

        // Start HTTP polling immediately
        startPolling();

        // Initialize UPnP and fetch metadata asynchronously
        initializeAsync();
    }

    /**
     * Begins HTTP polling for device status.
     * This is started immediately during initialization as it's critical
     * for basic device functionality.
     * 
     * Note: This method is called from initialize() and should not be called directly.
     */
    private void startPolling() {
        DeviceManager manager = deviceManager;
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

    /**
     * Performs asynchronous initialization steps including:
     * - UPnP setup
     * - Metadata service initialization
     * - Additional device feature setup
     * 
     * This is done async to avoid blocking the thing handler initialization.
     * The Thing will be marked ONLINE once all async initialization completes successfully.
     */
    private void initializeAsync() {
        DeviceManager manager = deviceManager;
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

    /**
     * Cleans up resources and stops all background operations.
     * This includes stopping HTTP polling and UPnP subscriptions.
     */
    @Override
    public void dispose() {
        logger.debug("Disposing ThingHandler for Thing: {}", getThing().getUID());

        DeviceManager manager = deviceManager;
        if (manager != null) {
            manager.dispose();
            deviceManager = null;
        }

        super.dispose();
    }

    /**
     * Handles commands received from OpenHAB core.
     * Delegates actual command execution to the DeviceManager.
     * 
     * Thread-safe: This method may be called from different threads by the OpenHAB framework.
     *
     * @param channelUID The channel that received the command
     * @param command The command to execute
     */
    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.trace("Received command: {} for channel: {}", command, channelUID.getIdWithoutGroup());

        final DeviceManager manager = deviceManager;
        if (manager != null) {
            manager.handleCommand(channelUID.getIdWithoutGroup(), command);
        } else {
            logger.warn("Cannot handle command - device manager not initialized");
        }
    }

    /**
     * Updates the state of a channel, supporting both direct channel IDs and group-prefixed channels.
     * Channel groups supported: playback, system, network, multiroom
     * 
     * Thread-safe: This method may be called from different threads by the device manager
     * or other asynchronous operations.
     *
     * @param channelId The channel ID, with or without group prefix
     * @param state The new state to set
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

    /**
     * Updates the Thing status with optional detail and description.
     * This method is thread-safe and can be called from any context.
     *
     * @param status The new Thing status
     * @param detail Optional status detail
     * @param description Optional description of the status
     */
    public final void handleStatusUpdate(ThingStatus status, @Nullable ThingStatusDetail detail,
            @Nullable String description) {
        updateStatus(status, detail != null ? detail : ThingStatusDetail.NONE, description != null ? description : "");
    }

    /**
     * Updates the Thing status without additional detail.
     * This is a convenience method for simple status updates.
     *
     * @param status The new Thing status
     */
    public final void handleStatusUpdate(ThingStatus status) {
        updateStatus(status);
    }

    /**
     * Updates the UDN (Unique Device Name) in the Thing configuration.
     * This is typically called during device discovery or when UPnP information is updated.
     *
     * @param udn The new UDN to store in configuration
     */
    public final void updateUdnInConfig(String udn) {
        Configuration config = editConfiguration();
        config.put("udn", udn);
        updateConfiguration(config);
        logger.debug("Updated UDN in configuration to: {}", udn);
    }

    /**
     * Gets the device manager instance.
     * This method supports multiroom functionality by allowing access to the device manager
     * from other components.
     *
     * @return The current device manager instance, or null if not initialized
     */
    public @Nullable DeviceManager getDeviceManager() {
        return deviceManager;
    }
}
