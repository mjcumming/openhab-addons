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
package org.openhab.binding.somecomfort.internal.handler;

import java.time.LocalTime;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.*;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.*;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.BridgeHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.openhab.binding.somecomfort.internal.SomecomfortBindingConstants;
import org.openhab.binding.somecomfort.internal.client.model.Device;
import org.openhab.binding.somecomfort.internal.client.exceptions.SomeComfortError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link SomecomfortHandler} is responsible for handling commands for Honeywell thermostats
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class SomecomfortHandler extends BaseThingHandler {
    private final Logger logger = LoggerFactory.getLogger(SomecomfortHandler.class);

    private @Nullable String deviceId;
    private @Nullable String locationId;
    private @Nullable ScheduledFuture<?> refreshJob;

    public SomecomfortHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        deviceId = (String) getConfig().get(SomecomfortBindingConstants.CONFIG_DEVICE_ID);
        locationId = (String) getConfig().get(SomecomfortBindingConstants.CONFIG_LOCATION_ID);

        if (deviceId == null || locationId == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Device ID and Location ID must be configured");
            return;
        }

        updateStatus(ThingStatus.UNKNOWN);
        scheduler.execute(this::updateStatus);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            updateStatus();
            return;
        }

        Device device = getDevice();
        if (device == null) {
            logger.debug("Cannot handle command - device not found");
            return;
        }

        try {
            String channelId = channelUID.getId();
            switch (channelId) {
                case SomecomfortBindingConstants.CHANNEL_SETPOINT_HEAT:
                    if (command instanceof DecimalType) {
                        double temp = ((DecimalType) command).doubleValue();
                        device.setHeatSetpoint(temp);
                    }
                    break;

                case SomecomfortBindingConstants.CHANNEL_SETPOINT_COOL:
                    if (command instanceof DecimalType) {
                        double temp = ((DecimalType) command).doubleValue();
                        device.setCoolSetpoint(temp);
                    }
                    break;

                case SomecomfortBindingConstants.CHANNEL_FAN_MODE:
                    if (command instanceof StringType) {
                        device.setFanMode(command.toString(), false);
                    }
                    break;

                case SomecomfortBindingConstants.CHANNEL_SYSTEM_MODE:
                    if (command instanceof StringType) {
                        device.setSystemMode(command.toString());
                    }
                    break;

                case SomecomfortBindingConstants.CHANNEL_HOLD_MODE:
                    if (command instanceof StringType) {
                        String mode = command.toString();
                        switch (mode) {
                            case SomecomfortBindingConstants.HOLD_NONE:
                                device.setHold(false, "Heat", null);
                                device.setHold(false, "Cool", null);
                                break;
                            case SomecomfortBindingConstants.HOLD_TEMPORARY:
                                // For temporary hold, default to 2 hours from now
                                LocalTime deadline = LocalTime.now().plusHours(2).withMinute(0);
                                device.setHold(false, "Heat", deadline);
                                device.setHold(false, "Cool", deadline);
                                break;
                            case SomecomfortBindingConstants.HOLD_PERMANENT:
                                device.setHold(true, "Heat", null);
                                device.setHold(true, "Cool", null);
                                break;
                        }
                    }
                    break;
            }

            // Update channels after command execution
            updateStatus();

        } catch (SomeComfortError e) {
            logger.debug("Error handling command {}: {}", command, e.getMessage());
        }
    }

    public void updateStatus() {
        Device device = getDevice();
        if (device == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
            return;
        }

        try {
            Bridge bridge = getBridge();
            if (bridge != null) {
                BridgeHandler bridgeHandler = bridge.getHandler();
                if (bridgeHandler instanceof SomecomfortBridgeHandler) {
                    ((SomecomfortBridgeHandler) bridgeHandler).updateDeviceData(device);
                }
            }

            // Update all channels
            updateState(SomecomfortBindingConstants.CHANNEL_TEMPERATURE,
                    toTemperatureState(device.getCurrentTemperature(), device.getTemperatureUnit()));

            updateState(SomecomfortBindingConstants.CHANNEL_SETPOINT_HEAT,
                    toTemperatureState(device.getSetpointHeat(), device.getTemperatureUnit()));

            updateState(SomecomfortBindingConstants.CHANNEL_SETPOINT_COOL,
                    toTemperatureState(device.getSetpointCool(), device.getTemperatureUnit()));

            Double humidity = device.getCurrentHumidity();
            if (humidity != null) {
                updateState(SomecomfortBindingConstants.CHANNEL_HUMIDITY,
                        new QuantityType<>(humidity, Units.PERCENT));
            } else {
                updateState(SomecomfortBindingConstants.CHANNEL_HUMIDITY, UnDefType.UNDEF);
            }

            // Handle outdoor temperature if available
            Double outdoorTemp = device.getOutdoorTemperature();
            if (outdoorTemp != null) {
                updateState(SomecomfortBindingConstants.CHANNEL_OUTDOOR_TEMPERATURE,
                        toTemperatureState(outdoorTemp, device.getTemperatureUnit()));
            } else {
                updateState(SomecomfortBindingConstants.CHANNEL_OUTDOOR_TEMPERATURE, UnDefType.UNDEF);
            }

            // Handle outdoor humidity if available
            Double outdoorHumidity = device.getOutdoorHumidity();
            if (outdoorHumidity != null) {
                updateState(SomecomfortBindingConstants.CHANNEL_OUTDOOR_HUMIDITY,
                        new QuantityType<>(outdoorHumidity, Units.PERCENT));
            } else {
                updateState(SomecomfortBindingConstants.CHANNEL_OUTDOOR_HUMIDITY, UnDefType.UNDEF);
            }

            String fanMode = device.getFanMode();
            if (fanMode != null) {
                updateState(SomecomfortBindingConstants.CHANNEL_FAN_MODE, new StringType(fanMode));
            }

            String systemMode = device.getSystemMode();
            if (systemMode != null) {
                updateState(SomecomfortBindingConstants.CHANNEL_SYSTEM_MODE, new StringType(systemMode));
            }

            String runningState = device.getEquipmentOutputStatus();
            if (runningState != null) {
                updateState(SomecomfortBindingConstants.CHANNEL_RUNNING_STATE, new StringType(runningState));
            }

            updateStatus(ThingStatus.ONLINE);

        } catch (SomeComfortError e) {
            logger.debug("Error updating device status: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    private State toTemperatureState(@Nullable Double temperature, @Nullable String unit) {
        if (temperature == null || unit == null) {
            return UnDefType.UNDEF;
        }
        return new QuantityType<>(temperature, "C".equals(unit) ? SIUnits.CELSIUS : ImperialUnits.FAHRENHEIT);
    }

    private @Nullable Device getDevice() {
        Bridge bridge = getBridge();
        if (bridge != null && deviceId != null) {
            BridgeHandler bridgeHandler = bridge.getHandler();
            if (bridgeHandler instanceof SomecomfortBridgeHandler) {
                return ((SomecomfortBridgeHandler) bridgeHandler).getDevice(deviceId);
            }
        }
        return null;
    }

    @Override
    public void dispose() {
        ScheduledFuture<?> job = refreshJob;
        if (job != null) {
            job.cancel(true);
            refreshJob = null;
        }
        deviceId = null;
        locationId = null;
    }
} 