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
package org.openhab.binding.somecomfort.internal.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalTime;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.openhab.binding.somecomfort.internal.client.exceptions.*;
import org.openhab.binding.somecomfort.internal.client.model.Device;
import org.openhab.binding.somecomfort.internal.client.model.Location;

/**
 * The {@link SomecomfortApi} class provides high-level access to the Honeywell Total Comfort API.
 * It handles authentication, device discovery, and all device operations.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class SomecomfortApi {
    private static final Logger logger = LoggerFactory.getLogger(SomecomfortApi.class);
    private static final Gson gson = new Gson();

    private final SomecomfortHttpClient client;
    private final Map<String, Location> locations = new ConcurrentHashMap<>();

    /**
     * Creates a new API client instance
     *
     * @param username Total Comfort account username
     * @param password Total Comfort account password
     */
    public SomecomfortApi(String username, String password) {
        this.client = new SomecomfortHttpClient(username, password);
    }

    /**
     * Logs in to the Total Comfort API and discovers all locations and devices
     *
     * @throws SomeComfortError if login fails or device discovery fails
     */
    public void login() throws SomeComfortError {
        client.login();
        discover();
    }

    /**
     * Discovers all locations and devices for the account
     *
     * @throws SomeComfortError if the API request fails
     */
    private void discover() throws SomeComfortError {
        JsonArray rawLocations = getLocationList();
        locations.clear();

        for (var element : rawLocations) {
            JsonObject locationObj = element.getAsJsonObject();
            Location location = new Location(locationObj);
            locations.put(location.getLocationId(), location);

            JsonArray devices = getDevicesForLocation(location.getLocationId());
            for (var deviceElement : devices) {
                JsonObject deviceObj = deviceElement.getAsJsonObject();
                Device device = new Device(deviceObj);
                location.addDevice(device);
            }
        }
    }

    /**
     * Gets the list of locations from the API
     *
     * @return JsonArray of location data
     * @throws SomeComfortError if the API request fails
     */
    private JsonArray getLocationList() throws SomeComfortError {
        Map<String, String> params = new HashMap<>();
        params.put("page", "1");
        params.put("filter", "");

        JsonObject response = client.post("/Location/GetLocationListData", params, null);
        return response.getAsJsonArray("Locations");
    }

    /**
     * Gets the list of devices for a location
     *
     * @param locationId the location ID to get devices for
     * @return JsonArray of device data
     * @throws SomeComfortError if the API request fails
     */
    private JsonArray getDevicesForLocation(String locationId) throws SomeComfortError {
        String body = String.format("{\"locationId\":\"%s\"}", locationId);
        JsonObject response = client.post("/Device/GetZoneListData", null, body);
        return response.getAsJsonArray("Devices");
    }

    /**
     * Updates a device's data from the API
     *
     * @param device the device to update
     * @throws SomeComfortError if the API request fails
     */
    public void updateDeviceData(Device device) throws SomeComfortError {
        String path = String.format("/Device/CheckDataSession/%s", device.getDeviceId());
        JsonObject response = client.get(path);
        if (!response.get("success").getAsBoolean()) {
            throw new APIError("Failed to get device data");
        }
        device.updateData(response);
    }

    /**
     * Updates device settings via the API
     *
     * @param device the device to update
     * @param settings map of settings to update
     * @throws SomeComfortError if the API request fails
     */
    public void setDeviceSettings(Device device, Map<String, Object> settings) throws SomeComfortError {
        Map<String, Object> data = new HashMap<>();
        data.put("SystemSwitch", null);
        data.put("HeatSetpoint", null);
        data.put("CoolSetpoint", null);
        data.put("HeatNextPeriod", null);
        data.put("CoolNextPeriod", null);
        data.put("StatusHeat", null);
        data.put("DeviceID", device.getDeviceId());
        data.putAll(settings);

        String body = gson.toJson(data);
        JsonObject response = client.post("/Device/SubmitControlScreenChanges", null, body);

        if (!response.get("success").getAsBoolean()) {
            throw new APIError("Failed to update device settings");
        }
    }

    /**
     * Gets all locations
     *
     * @return unmodifiable map of location IDs to locations
     */
    public Map<String, Location> getLocations() {
        return Collections.unmodifiableMap(locations);
    }

    /**
     * Gets a device by ID
     *
     * @param deviceId the device ID to look up
     * @return the device or null if not found
     */
    public @Nullable Device getDevice(String deviceId) {
        for (Location location : locations.values()) {
            Device device = location.getDevice(deviceId);
            if (device != null) {
                return device;
            }
        }
        return null;
    }

    /**
     * Sets the heat setpoint for a device
     *
     * @param device the device to update
     * @param temperature the new heat setpoint
     * @throws SomeComfortError if the API request fails
     */
    public void setHeatSetpoint(Device device, double temperature) throws SomeComfortError {
        Map<String, Object> settings = new HashMap<>();
        settings.put("HeatSetpoint", temperature);
        setDeviceSettings(device, settings);
    }

    /**
     * Sets the cool setpoint for a device
     *
     * @param device the device to update
     * @param temperature the new cool setpoint
     * @throws SomeComfortError if the API request fails
     */
    public void setCoolSetpoint(Device device, double temperature) throws SomeComfortError {
        Map<String, Object> settings = new HashMap<>();
        settings.put("CoolSetpoint", temperature);
        setDeviceSettings(device, settings);
    }

    /**
     * Sets the fan mode for a device
     *
     * @param device the device to update
     * @param mode the new fan mode
     * @param followSchedule whether to follow the schedule
     * @throws SomeComfortError if the mode is invalid or the API request fails
     */
    public void setFanMode(Device device, String mode, boolean followSchedule) throws SomeComfortError {
        Map<String, Object> settings = new HashMap<>();
        settings.put("FanMode", getFanModeIndex(mode));
        settings.put("FollowSchedule", followSchedule);
        setDeviceSettings(device, settings);
    }

    /**
     * Sets the system mode for a device
     *
     * @param device the device to update
     * @param mode the new system mode
     * @throws SomeComfortError if the mode is invalid or the API request fails
     */
    public void setSystemMode(Device device, String mode) throws SomeComfortError {
        Map<String, Object> settings = new HashMap<>();
        settings.put("SystemSwitch", getSystemModeIndex(mode));
        setDeviceSettings(device, settings);
    }

    /**
     * Sets a temperature hold for a device
     *
     * @param device the device to update
     * @param permanent whether this is a permanent hold
     * @param type the type of hold (Heat/Cool)
     * @param until when the hold should end (for temporary holds)
     * @throws SomeComfortError if the hold time is invalid or the API request fails
     */
    public void setHold(Device device, boolean permanent, String type, @Nullable LocalTime until) throws SomeComfortError {
        Map<String, Object> settings = new HashMap<>();
        if (permanent) {
            settings.put("Status" + type, 2); // Permanent hold
            settings.put(type + "NextPeriod", 0);
        } else if (until != null) {
            if (until.getMinute() % 15 != 0) {
                throw new SomeComfortError("Hold time must be on 15-minute boundary");
            }
            int quarterHours = (until.getHour() * 60 + until.getMinute()) / 15;
            settings.put("Status" + type, 1); // Temporary hold
            settings.put(type + "NextPeriod", quarterHours);
        } else {
            settings.put("Status" + type, 0); // No hold
            settings.put(type + "NextPeriod", 0);
        }
        setDeviceSettings(device, settings);
    }

    private int getFanModeIndex(String mode) throws SomeComfortError {
        switch (mode.toLowerCase()) {
            case "auto":
                return 0;
            case "on":
                return 1;
            case "circulate":
                return 2;
            case "followschedule":
                return 3;
            default:
                throw new SomeComfortError("Invalid fan mode: " + mode);
        }
    }

    private int getSystemModeIndex(String mode) throws SomeComfortError {
        switch (mode.toLowerCase()) {
            case "emheat":
                return 0;
            case "heat":
                return 1;
            case "off":
                return 2;
            case "cool":
                return 3;
            case "auto":
                return 4;
            default:
                throw new SomeComfortError("Invalid system mode: " + mode);
        }
    }

    /**
     * Closes the API client and clears all cached data
     */
    public void close() {
        client.close();
        locations.clear();
    }
} 