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

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.honeywelltcc.internal.client.HoneywellTCCHttpClient;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.HoneywellTCCException;
import org.openhab.binding.honeywelltcc.internal.config.HoneywellTCCThermostatConfiguration;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

/**
 * The HoneywellTCCThermostatHandler handles commands for thermostat channels,
 * updates channel states based on data from the bridge, and converts temperature values.
 * 
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class HoneywellTCCThermostatHandler extends BaseThingHandler {
    private final Logger logger = LoggerFactory.getLogger(HoneywellTCCThermostatHandler.class);
    private final Bridge bridge;
    private final HoneywellTCCHttpClient client;
    private final ScheduledExecutorService scheduler;
    private @Nullable HoneywellTCCThermostatConfiguration config;

    public HoneywellTCCThermostatHandler(Thing thing, Bridge bridge, HoneywellTCCHttpClient client,
            ScheduledExecutorService scheduler) {
        super(thing);
        this.bridge = bridge;
        this.client = client;
        this.scheduler = scheduler;
    }

    @Override
    public void initialize() {
        config = getConfigAs(HoneywellTCCThermostatConfiguration.class);
        if (!validateConfig()) {
            return;
        }
        // Register with the bridge to receive updates.
        if (bridge.getHandler() instanceof HoneywellTCCBridgeHandler bridgeHandler) {
            bridgeHandler.registerThermostatHandler(this);
            updateStatus(ThingStatus.ONLINE);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED);
        }
    }

    @Override
    public void dispose() {
        // Unregister this thermostat handler from the bridge.
        if (bridge.getHandler() instanceof HoneywellTCCBridgeHandler bridgeHandler) {
            bridgeHandler.unregisterThermostatHandler(getThing().getUID());
        }
        super.dispose();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // Ensure configuration is valid for non-null fields.
        if (config == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Configuration missing");
            return;
        }
        // Extract non-null device and location IDs
        String deviceId = Objects.requireNonNull(config.deviceId, "Device ID cannot be null");
        String locationId = Objects.requireNonNull(config.locationId, "Location ID cannot be null");

        try {
            switch (channelUID.getId()) {
                case CHANNEL_HEAT_SETPOINT:
                    if (command instanceof QuantityType<?>) {
                        QuantityType<?> quantity = (QuantityType<?>) command;
                        QuantityType<?> fahrenheit = quantity.toUnit(FAHRENHEIT);
                        if (fahrenheit != null) {
                            client.setHeatSetpoint(deviceId, locationId, fahrenheit.doubleValue());
                            updateState(channelUID, quantity);
                        }
                    }
                    break;
                case CHANNEL_COOL_SETPOINT:
                    if (command instanceof QuantityType<?>) {
                        QuantityType<?> quantity = (QuantityType<?>) command;
                        QuantityType<?> fahrenheit = quantity.toUnit(FAHRENHEIT);
                        if (fahrenheit != null) {
                            client.setCoolSetpoint(deviceId, locationId, fahrenheit.doubleValue());
                            updateState(channelUID, quantity);
                        }
                    }
                    break;
                case CHANNEL_FAN_MODE:
                    if (command instanceof StringType) {
                        client.setFanMode(deviceId, locationId, command.toString());
                        updateState(channelUID, (StringType) command);
                    }
                    break;
                // Add additional channel handling as needed.
                default:
                    logger.debug("Unrecognized channel {} for command {}", channelUID, command);
                    break;
            }
        } catch (HoneywellTCCException e) {
            logger.debug("Error handling command {}: {}", command, e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    /**
     * Updates the thermostat's channels based on device data.
     */
    public void updateData(JsonObject deviceData) {
        try {
            updateState(CHANNEL_INDOOR_TEMPERATURE,
                    new QuantityType<>(deviceData.get(API_KEY_INDOOR_TEMPERATURE).getAsDouble(), FAHRENHEIT));
            updateState(CHANNEL_INDOOR_HUMIDITY,
                    new QuantityType<>(deviceData.get(API_KEY_INDOOR_HUMIDITY).getAsDouble(), PERCENT));
            updateState(CHANNEL_MODE, new StringType(deviceData.get(API_KEY_SYSTEM_STATUS).getAsString()));
            updateState(CHANNEL_FAN_MODE, new StringType(deviceData.get(API_KEY_FAN_STATUS).getAsString()));
            updateState(CHANNEL_HEAT_SETPOINT,
                    new QuantityType<>(deviceData.get(API_KEY_HEAT_SETPOINT).getAsDouble(), FAHRENHEIT));
            updateState(CHANNEL_COOL_SETPOINT,
                    new QuantityType<>(deviceData.get(API_KEY_COOL_SETPOINT).getAsDouble(), FAHRENHEIT));

            updateStatus(ThingStatus.ONLINE);
        } catch (Exception e) {
            logger.debug("Error updating device data: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    /**
     * Checks if the provided device and location IDs match this thermostat configuration.
     */
    public boolean matchesDevice(String deviceId, String locationId) {
        return config != null && Objects.equals(config.deviceId, deviceId)
                && Objects.equals(config.locationId, locationId);
    }

    private boolean validateConfig() {
        if (config == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Configuration missing");
            return false;
        }
        if (config.deviceId == null || config.deviceId.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Device ID missing");
            return false;
        }
        if (config.locationId == null || config.locationId.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Location ID missing");
            return false;
        }
        return true;
    }
}
