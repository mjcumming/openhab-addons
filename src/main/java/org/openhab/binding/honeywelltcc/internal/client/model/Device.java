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
package org.openhab.binding.somecomfort.internal.client.model;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import com.google.gson.JsonObject;

/**
 * The {@link Device} class represents a Honeywell thermostat device in the Total Comfort system.
 * It maintains the device state and provides access to all device capabilities.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class Device {
    public static final String[] FAN_MODES = { "auto", "on", "circulate", "follow schedule" };
    public static final String[] SYSTEM_MODES = { "emheat", "heat", "off", "cool", "auto" };
    public static final String[] EQUIPMENT_STATUS = { "off/fan", "heat", "cool" };

    private final String deviceId;
    private final String name;
    private final @Nullable String macId;

    private @Nullable JsonObject data;
    private @Nullable JsonObject uiData;
    private @Nullable JsonObject fanData;
    private @Nullable JsonObject drData;
    private boolean deviceLive;
    private boolean communicationLost;

    /**
     * Creates a new Device instance from JSON data
     *
     * @param data JSON object containing device data from the API
     * @throws IllegalArgumentException if required fields are missing from the JSON
     */
    public Device(JsonObject data) {
        if (!data.has("DeviceID") || !data.has("Name")) {
            throw new IllegalArgumentException("Device data missing required fields");
        }
        this.deviceId = data.get("DeviceID").getAsString();
        this.name = data.get("Name").getAsString();
        this.macId = data.has("MacID") ? data.get("MacID").getAsString() : null;
    }

    /**
     * Updates the device state with new data from the API
     *
     * @param data JSON object containing updated device state
     */
    public void updateData(JsonObject data) {
        this.data = data;
        this.uiData = data.getAsJsonObject("uiData");
        this.fanData = data.getAsJsonObject("fanData");
        this.drData = data.getAsJsonObject("drData");
        this.deviceLive = data.get("deviceLive").getAsBoolean();
        this.communicationLost = data.get("communicationLost").getAsBoolean();
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getName() {
        return name;
    }

    public @Nullable String getMacId() {
        return macId;
    }

    /**
     * Checks if the device is currently online and communicating
     *
     * @return true if the device is alive and communicating
     */
    public boolean isAlive() {
        return deviceLive && !communicationLost;
    }

    /**
     * Gets the current fan running state
     *
     * @return true if the fan is currently running
     */
    public boolean getFanRunning() {
        JsonObject localFanData = fanData;
        return localFanData != null && localFanData.has("fanIsRunning") 
            && localFanData.get("fanIsRunning").getAsBoolean();
    }

    /**
     * Gets the current temperature reading
     *
     * @return the current temperature or null if not available
     */
    public @Nullable Double getCurrentTemperature() {
        return getDoubleValue(uiData, "DispTemperature");
    }

    /**
     * Gets the heat setpoint
     *
     * @return the heat setpoint or null if not available
     */
    public @Nullable Double getSetpointHeat() {
        return getDoubleValue(uiData, "HeatSetpoint");
    }

    /**
     * Gets the cool setpoint
     *
     * @return the cool setpoint or null if not available
     */
    public @Nullable Double getSetpointCool() {
        return getDoubleValue(uiData, "CoolSetpoint");
    }

    /**
     * Gets the current indoor humidity reading
     *
     * @return the current humidity or null if not available
     */
    public @Nullable Double getCurrentHumidity() {
        return getDoubleValue(uiData, "IndoorHumidity");
    }

    /**
     * Gets the outdoor temperature if available
     *
     * @return the outdoor temperature or null if not available
     */
    public @Nullable Double getOutdoorTemperature() {
        JsonObject localUiData = uiData;
        if (localUiData != null && localUiData.has("OutdoorTemperatureAvailable")
                && localUiData.get("OutdoorTemperatureAvailable").getAsBoolean()) {
            return getDoubleValue(localUiData, "OutdoorTemperature");
        }
        return null;
    }

    /**
     * Gets the outdoor humidity if available
     *
     * @return the outdoor humidity or null if not available
     */
    public @Nullable Double getOutdoorHumidity() {
        JsonObject localUiData = uiData;
        if (localUiData != null && localUiData.has("OutdoorHumidityAvailable")
                && localUiData.get("OutdoorHumidityAvailable").getAsBoolean()) {
            return getDoubleValue(localUiData, "OutdoorHumidity");
        }
        return null;
    }

    /**
     * Gets the current fan mode
     *
     * @return the fan mode string or null if not available
     */
    public @Nullable String getFanMode() {
        JsonObject localFanData = fanData;
        if (localFanData != null && localFanData.has("fanMode")) {
            int mode = localFanData.get("fanMode").getAsInt();
            return mode >= 0 && mode < FAN_MODES.length ? FAN_MODES[mode] : null;
        }
        return null;
    }

    /**
     * Gets the current system mode
     *
     * @return the system mode string or null if not available
     */
    public @Nullable String getSystemMode() {
        JsonObject localUiData = uiData;
        if (localUiData != null && localUiData.has("SystemSwitchPosition")) {
            int mode = localUiData.get("SystemSwitchPosition").getAsInt();
            return mode >= 0 && mode < SYSTEM_MODES.length ? SYSTEM_MODES[mode] : null;
        }
        return null;
    }

    /**
     * Gets the current equipment output status
     *
     * @return the equipment status string or null if not available
     */
    public @Nullable String getEquipmentOutputStatus() {
        JsonObject localUiData = uiData;
        if (localUiData != null && localUiData.has("EquipmentOutputStatus")) {
            int status = localUiData.get("EquipmentOutputStatus").getAsInt();
            if (status == 0) {
                return getFanRunning() ? "fan" : "off";
            }
            return status >= 0 && status < EQUIPMENT_STATUS.length ? EQUIPMENT_STATUS[status] : null;
        }
        return null;
    }

    /**
     * Gets the temperature unit (F/C)
     *
     * @return the temperature unit string or null if not available
     */
    public @Nullable String getTemperatureUnit() {
        JsonObject localUiData = uiData;
        return localUiData != null && localUiData.has("DisplayUnits") ? 
            localUiData.get("DisplayUnits").getAsString() : null;
    }

    private @Nullable Double getDoubleValue(@Nullable JsonObject obj, String key) {
        if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsDouble();
        }
        return null;
    }

    /**
     * Gets a copy of the raw UI data
     *
     * @return copy of UI data or null if not available
     */
    public @Nullable JsonObject getRawUiData() {
        JsonObject localUiData = uiData;
        return localUiData != null ? localUiData.deepCopy() : null;
    }

    /**
     * Gets a copy of the raw fan data
     *
     * @return copy of fan data or null if not available
     */
    public @Nullable JsonObject getRawFanData() {
        JsonObject localFanData = fanData;
        return localFanData != null ? localFanData.deepCopy() : null;
    }

    /**
     * Gets a copy of the raw DR data
     *
     * @return copy of DR data or null if not available
     */
    public @Nullable JsonObject getRawDrData() {
        JsonObject localDrData = drData;
        return localDrData != null ? localDrData.deepCopy() : null;
    }

    @Override
    public String toString() {
        return String.format("Device [id=%s, name=%s, alive=%b]", deviceId, name, isAlive());
    }
} 