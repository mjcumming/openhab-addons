package org.openhab.binding.somecomfort.internal.client;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonObject;

/**
 * The {@link Location} class represents a physical location in the Honeywell Total Comfort system.
 * Each location can contain multiple devices (thermostats).
 *
 * @author Your Name - Initial contribution
 */
@NonNullByDefault
public class Location {
    private final String locationId;
    private final String name;
    private final Map<String, Device> devices = new ConcurrentHashMap<>();

    /**
     * Creates a new Location instance from JSON data
     *
     * @param data JSON object containing location data from the API
     * @throws IllegalArgumentException if required fields are missing from the JSON
     */
    public Location(JsonObject data) {
        if (!data.has("LocationID") || !data.has("Name")) {
            throw new IllegalArgumentException("Location data missing required fields");
        }
        this.locationId = data.get("LocationID").getAsString();
        this.name = data.get("Name").getAsString();
    }

    /**
     * Gets the unique identifier for this location
     *
     * @return the location ID
     */
    public String getLocationId() {
        return locationId;
    }

    /**
     * Gets the name of this location
     *
     * @return the location name
     */
    public String getName() {
        return name;
    }

    /**
     * Adds a device to this location
     *
     * @param device the device to add
     */
    public void addDevice(Device device) {
        devices.put(device.getDeviceId(), device);
    }

    /**
     * Gets a device by its ID
     *
     * @param deviceId the device ID to look up
     * @return the device, or null if not found
     */
    public @Nullable Device getDevice(String deviceId) {
        return devices.get(deviceId);
    }

    /**
     * Gets an unmodifiable map of all devices at this location
     *
     * @return map of device IDs to devices
     */
    public Map<String, Device> getDevices() {
        return Collections.unmodifiableMap(devices);
    }

    @Override
    public String toString() {
        return String.format("Location [id=%s, name=%s, devices=%d]", locationId, name, devices.size());
    }
} 