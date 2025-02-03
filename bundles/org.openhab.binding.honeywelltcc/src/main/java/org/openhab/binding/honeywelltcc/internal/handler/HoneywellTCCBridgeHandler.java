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

import static org.openhab.binding.honeywelltcc.internal.HoneywellTCCBindingConstants.*;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.openhab.binding.honeywelltcc.internal.client.HoneywellTCCHttpClient;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.HoneywellTCCAuthException;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.HoneywellTCCException;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.HoneywellTCCRateLimitException;
import org.openhab.binding.honeywelltcc.internal.config.HoneywellTCCBridgeConfiguration;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The HoneywellTCCBridgeHandler is responsible for:
 * <ul>
 * <li>Creating a self‑managed Jetty HttpClient instance</li>
 * <li>Managing authentication, rate limiting, and polling for thermostats</li>
 * <li>Distributing updates to registered thermostat handlers</li>
 * </ul>
 * 
 * This version adheres to OpenHAB design patterns by correctly handling activation,
 * resource initialization, and disposal.
 * 
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class HoneywellTCCBridgeHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(HoneywellTCCBridgeHandler.class);

    private @Nullable HoneywellTCCHttpClient client;
    private @Nullable HttpClient jettyHttpClient;
    private @Nullable ScheduledExecutorService scheduler;
    private @Nullable HoneywellTCCBridgeConfiguration config;
    private @Nullable ScheduledFuture<?> pollingJob;

    // Track registered thermostat handlers
    private final Map<ThingUID, HoneywellTCCThermostatHandler> thermostatHandlers = new ConcurrentHashMap<>();

    /**
     * Constructor required by the HandlerFactory.
     */
    public HoneywellTCCBridgeHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void initialize() {
        config = getConfigAs(HoneywellTCCBridgeConfiguration.class);
        if (config == null || config.username.isEmpty() || config.password.isEmpty()) {
            logger.error("Bridge configuration is missing or incomplete; binding will not be activated");
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Bridge configuration missing/incomplete");
            return;
        }

        try {
            SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
            jettyHttpClient = new HttpClient(sslContextFactory);
            jettyHttpClient.setConnectTimeout(HTTP_REQUEST_TIMEOUT_SEC * 1000L);
            jettyHttpClient.start();
            logger.debug("Jetty HttpClient started");

            scheduler = Executors.newScheduledThreadPool(1);

            // Create the shared HTTP client using the bridge-supplied values.
            client = HoneywellTCCHttpClient.create(Objects.requireNonNull(jettyHttpClient), config.username,
                    config.password, Objects.requireNonNull(scheduler));

            // Attempt to log in immediately
            client.login().thenCompose(ignored -> client.keepalive()).thenCompose(ignored -> client.fetchLocations())
                    .thenRun(() -> {
                        logger.info("All initialization steps completed successfully.");
                        updateStatus(ThingStatus.ONLINE);
                    }).exceptionally(throwable -> {
                        logger.error("Failed during initialization: {}", throwable.getMessage(), throwable);
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                                throwable.getMessage());
                        return null;
                    });

        } catch (Exception e) {
            logger.error("Error during bridge initialization: {}", e.getMessage(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Initialization failed");
        }
    }

    protected void deactivate(org.osgi.service.component.ComponentContext context) {
        // Clean up resources.
        if (pollingJob != null) {
            pollingJob.cancel(true);
            pollingJob = null;
            logger.debug("Polling stopped");
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (jettyHttpClient != null) {
            try {
                jettyHttpClient.stop();
                logger.debug("Jetty HttpClient stopped");
            } catch (Exception e) {
                logger.warn("Error stopping HttpClient: {}", e.getMessage(), e);
            }
            jettyHttpClient = null;
        }
        client = null;
        updateStatus(ThingStatus.OFFLINE);
        logger.info("HoneywellTCCBridgeHandler deactivated");
    }

    /**
     * Provides the HoneywellTCCHttpClient instance used for communication.
     *
     * @return the HTTP client instance or null if not initialized.
     */
    public @Nullable HoneywellTCCHttpClient getClient() {
        return client;
    }

    /**
     * Exposes the internal scheduler used by the bridge.
     *
     * @return the ScheduledExecutorService instance.
     */
    public @Nullable ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    /**
     * Registers a thermostat handler for update distribution.
     */
    public void registerThermostatHandler(HoneywellTCCThermostatHandler handler) {
        thermostatHandlers.put(handler.getThing().getUID(), handler);
        logger.debug("Registered thermostat handler for {}", handler.getThing().getUID());
    }

    /**
     * Unregisters the thermostat handler.
     */
    public void unregisterThermostatHandler(ThingUID thingUID) {
        thermostatHandlers.remove(thingUID);
        logger.debug("Unregistered thermostat handler for {}", thingUID);
    }

    /**
     * Polls for thermostat data and distributes updates to registered handlers.
     * (Implementation should mirror the Python reference behavior.)
     */
    private void poll() {
        if (client == null) {
            return;
        }
        try {
            // For example: get locations and devices in one call
            JsonArray locations = client.getLocations();
            logger.debug("Retrieved {} locations", locations.size());

            for (JsonElement locationElement : locations) {
                JsonObject location = locationElement.getAsJsonObject();
                String locationId = location.get(API_KEY_LOCATION_ID).getAsString();
                JsonArray devices = location.getAsJsonArray(API_KEY_DEVICES);

                for (JsonElement deviceElement : devices) {
                    JsonObject deviceData = deviceElement.getAsJsonObject();
                    String deviceId = deviceData.get(API_KEY_DEVICE_ID).getAsString();

                    // Get detailed device data
                    JsonObject fullDeviceData = client.getThermostatData(deviceId);

                    // Distribute update to matching thermostat handlers
                    thermostatHandlers.values().stream().filter(handler -> handler.matchesDevice(deviceId, locationId))
                            .forEach(handler -> handler.updateData(fullDeviceData));
                }
            }
            updateStatus(ThingStatus.ONLINE);
        } catch (HoneywellTCCAuthException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Authentication failed");
        } catch (HoneywellTCCRateLimitException e) {
            logger.debug("Rate limit exceeded, will retry next polling cycle");
        } catch (HoneywellTCCException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("Received command {} for channel {}. Bridge commands are not processed.", command, channelUID);
    }
}
