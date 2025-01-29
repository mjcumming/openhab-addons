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
package org.openhab.binding.honeywelltcc.internal.handler;

import java.util.Collection;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.types.Command;
import org.openhab.binding.honeywelltcc.internal.HoneywellTccBindingConstants;
import org.openhab.binding.honeywelltcc.internal.client.HoneywellTccClient;
import org.openhab.binding.honeywelltcc.internal.client.model.Location;
import org.openhab.binding.honeywelltcc.internal.client.model.Device;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.AuthError;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.HoneywellTccError;
import org.openhab.binding.honeywelltcc.internal.discovery.HoneywellTccDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledExecutorService;

/**
 * The {@link HoneywellTccBridgeHandler} is responsible for handling commands and managing the connection
 * to a Honeywell Total Comfort Control account.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class HoneywellTccBridgeHandler extends BaseBridgeHandler {
    private static final int DEFAULT_POLLING_INTERVAL = 120; // 2 minutes
    private static final int MIN_POLLING_INTERVAL = 60; // 1 minute

    private final Logger logger = LoggerFactory.getLogger(HoneywellTccBridgeHandler.class);
    private final ScheduledExecutorService scheduler;
    private final BiFunction<String, String, HoneywellTccClient> clientProvider;
    private final Runnable clientRemover;

    private @Nullable HoneywellTccClient client;
    private @Nullable ScheduledFuture<?> pollFuture;
    private @Nullable HoneywellTccDiscoveryService discoveryService;

    public HoneywellTccBridgeHandler(Bridge bridge, ScheduledExecutorService scheduler,
            BiFunction<String, String, HoneywellTccClient> clientProvider, Runnable clientRemover) {
        super(bridge);
        this.scheduler = scheduler;
        this.clientProvider = clientProvider;
        this.clientRemover = clientRemover;
    }

    @Override
    public void initialize() {
        updateStatus(ThingStatus.UNKNOWN);
        scheduler.execute(this::initializeClient);
    }

    private void initializeClient() {
        String username = (String) getConfig().get(HoneywellTccBindingConstants.CONFIG_USERNAME);
        String password = (String) getConfig().get(HoneywellTccBindingConstants.CONFIG_PASSWORD);
        Integer pollingInterval = (Integer) getConfig().get(HoneywellTccBindingConstants.CONFIG_POLLING_INTERVAL);

        if (username == null || password == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Username and password must be configured");
            return;
        }

        try {
            client = new HoneywellTccClient(username, password);
            client.login();
            updateStatus(ThingStatus.ONLINE);

            // Start polling with validated interval
            int interval = validatePollingInterval(pollingInterval);
            startPolling(interval);

            // Trigger discovery
            scheduleDeviceDiscovery();

        } catch (AuthError e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Authentication failed: " + e.getMessage());
        } catch (HoneywellTccError e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Connection failed: " + e.getMessage());
        }
    }

    private int validatePollingInterval(@Nullable Integer pollingInterval) {
        if (pollingInterval == null || pollingInterval < MIN_POLLING_INTERVAL) {
            logger.debug("Invalid polling interval {}. Using default of {} seconds", pollingInterval,
                    DEFAULT_POLLING_INTERVAL);
            return DEFAULT_POLLING_INTERVAL;
        }
        return pollingInterval;
    }

    private void scheduleDeviceDiscovery() {
        HoneywellTccDiscoveryService service = discoveryService;
        if (service != null) {
            scheduler.execute(service::discoverDevices);
        }
    }

    private void startPolling(int pollingInterval) {
        stopPolling();
        pollFuture = scheduler.scheduleWithFixedDelay(this::poll, 0, pollingInterval, TimeUnit.SECONDS);
        logger.debug("Started polling with interval of {} seconds", pollingInterval);
    }

    private void stopPolling() {
        ScheduledFuture<?> future = pollFuture;
        if (future != null) {
            future.cancel(true);
            pollFuture = null;
        }
    }

    private void poll() {
        HoneywellTccClient client = this.client;
        if (client == null) {
            return;
        }

        try {
            // Update device data from API
            updateDeviceData();
            updateStatus(ThingStatus.ONLINE);

            // Update all child devices
            getThing().getThings().forEach(thing -> {
                if (thing.getHandler() instanceof HoneywellTccHandler) {
                    ((HoneywellTccHandler) thing.getHandler()).updateStatus();
                }
            });
        } catch (AuthError e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Authentication failed during refresh: " + e.getMessage());
            // Try to re-authenticate
            scheduler.schedule(this::initializeClient, 60, TimeUnit.SECONDS);
        } catch (HoneywellTccError e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Connection failed during refresh: " + e.getMessage());
        }
    }

    public void updateDeviceData(Device device) throws HoneywellTccError {
        HoneywellTccClient client = this.client;
        if (client != null) {
            client.updateDeviceData(device);
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // Bridge has no channels to handle commands
    }

    @Override
    public void dispose() {
        stopPolling();
        HoneywellTccClient client = this.client;
        if (client != null) {
            client.close();
            this.client = null;
        }
        clientRemover.run();
    }

    public void setDiscoveryService(HoneywellTccDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    public String getBridgeId() {
        return getThing().getUID().getId();
    }

    @Nullable
    public Collection<Location> getLocations() {
        HoneywellTccClient client = this.client;
        if (client != null) {
            return client.getLocations().values();
        }
        return null;
    }

    @Nullable
    public Device getDevice(String deviceId) {
        HoneywellTccClient client = this.client;
        if (client != null) {
            return client.getDevice(deviceId);
        }
        return null;
    }
} 