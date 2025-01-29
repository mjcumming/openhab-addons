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
package org.openhab.binding.honeywelltcc.internal.client;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.honeywelltcc.internal.client.exceptions.HoneywellTCCError;
import org.openhab.binding.honeywelltcc.internal.client.model.Device;
import org.openhab.binding.honeywelltcc.internal.client.model.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Client for Honeywell Total Comfort Control API
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class HoneywellTCCApi {
    private final Logger logger = LoggerFactory.getLogger(HoneywellTCCApi.class);
    private final HoneywellTCCHttpClient httpClient;
    private final Map<String, Location> locations = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();

    public HoneywellTCCApi(String username, String password) {
        this.httpClient = new HoneywellTCCHttpClient(username, password);
    }

    public void login() throws HoneywellTCCError {
        httpClient.login();
        updateLocations();
    }

    private void updateLocations() throws HoneywellTCCError {
        String response = httpClient.get("/Location/GetLocationListData");
        JsonArray locationList = JsonParser.parseString(response).getAsJsonArray();

        locations.clear();
        for (JsonElement element : locationList) {
            JsonObject locationData = element.getAsJsonObject();
            Location location = new Location(locationData);
            locations.put(location.getLocationId(), location);
        }
    }

    public Map<String, Location> getLocations() {
        return new HashMap<>(locations);
    }

    public @Nullable Device getDevice(String deviceId) {
        for (Location location : locations.values()) {
            Device device = location.getDevice(deviceId);
            if (device != null) {
                return device;
            }
        }
        return null;
    }

    public void updateDeviceData(Device device) throws HoneywellTCCError {
        String deviceId = device.getDeviceId();
        String response = httpClient.get("/Device/CheckDataSession/" + deviceId);
        JsonObject deviceData = JsonParser.parseString(response).getAsJsonObject();
        device.update(deviceData);
    }

    public void setHeatSetpoint(Device device, double temperature) throws HoneywellTCCError {
        Map<String, String> data = new HashMap<>();
        data.put("DeviceID", device.getDeviceId());
        data.put("HeatSetpoint", String.valueOf(temperature));
        data.put("StatusHeat", "1");
        data.put("SystemSwitch", device.getSystemMode());

        httpClient.post("/Device/SubmitControlScreenChanges", data);
    }

    public void setCoolSetpoint(Device device, double temperature) throws HoneywellTCCError {
        Map<String, String> data = new HashMap<>();
        data.put("DeviceID", device.getDeviceId());
        data.put("CoolSetpoint", String.valueOf(temperature));
        data.put("StatusCool", "1");
        data.put("SystemSwitch", device.getSystemMode());

        httpClient.post("/Device/SubmitControlScreenChanges", data);
    }

    public void setSystemMode(Device device, String mode) throws HoneywellTCCError {
        Map<String, String> data = new HashMap<>();
        data.put("DeviceID", device.getDeviceId());
        data.put("SystemSwitch", mode);

        httpClient.post("/Device/SubmitControlScreenChanges", data);
    }

    public void setFanMode(Device device, String mode, boolean isTemporary) throws HoneywellTCCError {
        Map<String, String> data = new HashMap<>();
        data.put("DeviceID", device.getDeviceId());
        data.put("FanMode", mode);
        data.put("FanModeIsTemp", String.valueOf(isTemporary));

        httpClient.post("/Device/SubmitControlScreenChanges", data);
    }

    public void setHold(Device device, String type, boolean enabled, @Nullable String until) throws HoneywellTCCError {
        Map<String, String> data = new HashMap<>();
        data.put("DeviceID", device.getDeviceId());
        data.put("StatusHeat", "1");
        data.put("StatusCool", "1");
        data.put(type + "NextPeriod", enabled ? "0" : "1");
        if (until != null) {
            data.put(type + "HoldUntil", until);
        }

        httpClient.post("/Device/SubmitControlScreenChanges", data);
    }

    public void close() {
        httpClient.close();
    }
} 