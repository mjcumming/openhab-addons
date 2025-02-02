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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
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
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The HoneywellTCCBridgeHandler manages the connection to the Honeywell TCC service.
 * It handles authentication, session maintenance, and polling of thermostat data.
 *
 * Note: This handler does not reference device-specific fields such as deviceId or locationId.
 * Those belong to the thermostat handler.
 * 
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
@Component(immediate = true, service = HoneywellTCCBridgeHandler.class)
public class HoneywellTCCBridgeHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(HoneywellTCCBridgeHandler.class);
    private final ScheduledExecutorService scheduler;
    private @Nullable HoneywellTCCBridgeConfiguration config;
    private @Nullable HoneywellTCCHttpClient client;
    private @Nullable ScheduledFuture<?> pollingJob;

    // Track registered thermostats
    private final Map<ThingUID, HoneywellTCCThermostatHandler> thermostatHandlers = new ConcurrentHashMap<>();

    // Inject the Jetty HttpClient directly via OSGi.
    @Reference
    private @Nullable HttpClient jettyClient;

    /**
     * Constructor accepts only the Bridge and the Scheduler.
     */
    public HoneywellTCCBridgeHandler(Bridge bridge, ScheduledExecutorService scheduler) {
        super(bridge);
        this.scheduler = scheduler;
        try {
            // Ensure the injected Jetty HttpClient is non-null.
            HttpClient nonNullJettyClient = Objects.requireNonNull(jettyClient,
                    "Injected Jetty HttpClient must not be null");
            // Create the HoneywellTCCHttpClient instance using the injected Jetty HttpClient.
            // Replace "username" and "password" with actual configuration values as appropriate.
            this.client = HoneywellTCCHttpClient.create(nonNullJettyClient, "username", "password", scheduler);
        } catch (HoneywellTCCException e) {
            logger.error("Failed to initialize the Honeywell TCC HTTP client: {}", e.getMessage());
            this.client = null;
        }
    }

    /**
     * Returns the HTTP client instance for use by thermostat handlers.
     */
    public @Nullable HoneywellTCCHttpClient getClient() {
        return this.client;
    }

    /**
     * For bridge handlers, command handling is not processed.
     * We simply log any received commands.
     */
    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("Received command {} for channel {}. Bridge commands are not processed.", command, channelUID);
    }

    @Override
    public void initialize() {
        config = getConfigAs(HoneywellTCCBridgeConfiguration.class);

        if (!validateConfig()) {
            return;
        }

        try {
            if (client != null && !client.isAuthenticated()) {
                client.login();
            }
            logger.info("Honeywell TCC Bridge initialized successfully.");
            startPolling();
        } catch (HoneywellTCCAuthException e) {
            logger.error("Authentication failed during bridge initialization: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Authentication error");
        } catch (HoneywellTCCRateLimitException e) {
            logger.error("Rate limit reached during bridge initialization: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Rate limit error");
        } catch (HoneywellTCCException e) {
            logger.error("Error initializing Honeywell TCC Bridge: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    @Override
    public void dispose() {
        stopPolling();
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                logger.debug("Error closing HTTP client", e);
            }
            client = null;
        }
        thermostatHandlers.clear();
        super.dispose();
    }

    private void startPolling() {
        stopPolling();
        if (config != null) {
            pollingJob = scheduler.scheduleWithFixedDelay(this::poll, 0, config.refresh, TimeUnit.MINUTES);
            logger.debug("Polling started with interval of {} minutes", config.refresh);
        }
    }

    private void stopPolling() {
        if (pollingJob != null) {
            pollingJob.cancel(true);
            pollingJob = null;
            logger.debug("Polling stopped");
        }
    }

    private void poll() {
        if (client == null) {
            return;
        }

        try {
            // Get all locations and devices in a single call
            JsonArray locations = client.getLocations();
            logger.debug("Retrieved {} locations", locations.size());

            // Process each location and its devices
            for (JsonElement locationElement : locations) {
                JsonObject location = locationElement.getAsJsonObject();
                String locationId = location.get(API_KEY_LOCATION_ID).getAsString();
                JsonArray devices = location.getAsJsonArray(API_KEY_DEVICES);

                // Update each device
                for (JsonElement deviceElement : devices) {
                    JsonObject deviceData = deviceElement.getAsJsonObject();
                    String deviceId = deviceData.get(API_KEY_DEVICE_ID).getAsString();

                    // Get detailed device data
                    JsonObject fullDeviceData = client.getThermostatData(deviceId);

                    // Find and update corresponding thermostat handler
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

    public void registerThermostatHandler(HoneywellTCCThermostatHandler handler) {
        thermostatHandlers.put(handler.getThing().getUID(), handler);
        logger.debug("Registered thermostat handler for {}", handler.getThing().getUID());
    }

    public void unregisterThermostatHandler(ThingUID thingUID) {
        thermostatHandlers.remove(thingUID);
        logger.debug("Unregistered thermostat handler for {}", thingUID);
    }

    private boolean validateConfig() {
        if (config == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Configuration missing");
            return false;
        }

        if (config.username.isEmpty() || config.password.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Username or password missing");
            return false;
        }

        if (config.refresh < MIN_REFRESH_MINUTES || config.refresh > MAX_REFRESH_MINUTES) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, String.format(
                    "Refresh interval must be between %d and %d minutes", MIN_REFRESH_MINUTES, MAX_REFRESH_MINUTES));
            return false;
        }

        return true;
    }
}
