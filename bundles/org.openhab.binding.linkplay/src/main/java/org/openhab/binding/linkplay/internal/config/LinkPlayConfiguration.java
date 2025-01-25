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
package org.openhab.binding.linkplay.internal.config;

import java.math.BigDecimal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.config.core.Configuration;

/**
 * Configuration class for the binding.
 * Follows OpenHAB configuration patterns for thing configuration.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayConfiguration {

    // We keep a default, but no min or max
    private static final int DEFAULT_PLAYER_STATUS_POLLING_INTERVAL = 5;
    private static final int DEFAULT_DEVICE_STATUS_POLLING_INTERVAL = 10;

    private String ipAddress = "";
    private String deviceName = "";
    private String udn = "";
    private int playerStatusPollingInterval = DEFAULT_PLAYER_STATUS_POLLING_INTERVAL;
    private int deviceStatusPollingInterval = DEFAULT_DEVICE_STATUS_POLLING_INTERVAL;

    /**
     * Creates a new configuration instance from a {@link Configuration} object.
     *
     * @param configuration The configuration object
     * @return A new configuration instance
     */
    public static LinkPlayConfiguration fromConfiguration(Configuration config) {
        LinkPlayConfiguration deviceConfig = new LinkPlayConfiguration();

        // Get IP address as string
        Object ipObj = config.get("ipAddress");
        if (ipObj instanceof String) {
            deviceConfig.ipAddress = (String) ipObj;
        }

        // Get device name as string
        Object nameObj = config.get("deviceName");
        if (nameObj instanceof String) {
            deviceConfig.deviceName = (String) nameObj;
        }

        // Get UDN as string
        Object udnObj = config.get("udn");
        if (udnObj instanceof String) {
            deviceConfig.udn = (String) udnObj;
        }

        // Get polling intervals as integers
        Object playerIntervalObj = config.get("playerStatusPollingInterval");
        if (playerIntervalObj instanceof BigDecimal) {
            deviceConfig.playerStatusPollingInterval = ((BigDecimal) playerIntervalObj).intValue();
        }

        Object deviceIntervalObj = config.get("deviceStatusPollingInterval");
        if (deviceIntervalObj instanceof BigDecimal) {
            deviceConfig.deviceStatusPollingInterval = ((BigDecimal) deviceIntervalObj).intValue();
        }

        return deviceConfig;
    }

    public void put(String key, String value) {
        switch (key) {
            case "ipAddress":
                this.ipAddress = value;
                break;
            case "deviceName":
                this.deviceName = value;
                break;
            case "udn":
                this.udn = value;
                break;
            default:
                // Ignore unknown keys
                break;
        }
    }

    public void put(String key, int value) {
        switch (key) {
            case "playerStatusPollingInterval":
                this.playerStatusPollingInterval = value;
                break;
            case "deviceStatusPollingInterval":
                this.deviceStatusPollingInterval = value;
                break;
            default:
                // Ignore unknown keys
                break;
        }
    }

    /**
     * Validates the configuration. Only IP address is required.
     * UDN will be discovered via HTTP if not provided.
     *
     * @return true if config is valid, false otherwise
     */
    public boolean isValid() {
        // Only IP address is required
        if (ipAddress.isEmpty()) {
            return false;
        }

        // Validate IP address format
        String[] octets = ipAddress.split("\\.");
        if (octets.length != 4) {
            return false;
        }

        try {
            for (String octet : octets) {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) {
                    return false;
                }
            }
        } catch (NumberFormatException e) {
            return false;
        }

        return true;
    }

    // ---- Getters ----
    public String getIpAddress() {
        return ipAddress;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getUdn() {
        return udn;
    }

    /**
     * Gets the player status polling interval in seconds
     * 
     * @return polling interval, or 0 if polling is disabled
     */
    public int getPlayerStatusPollingInterval() {
        return playerStatusPollingInterval;
    }

    /**
     * Gets the device status polling interval in seconds
     * 
     * @return polling interval, or 0 if polling is disabled
     */
    public int getDeviceStatusPollingInterval() {
        return deviceStatusPollingInterval;
    }

    @Override
    public String toString() {
        return String.format(
                "LinkPlayConfiguration [ipAddress=%s, deviceName=%s, udn=%s, playerStatusPollingInterval=%d, deviceStatusPollingInterval=%d]",
                ipAddress, deviceName, udn, playerStatusPollingInterval, deviceStatusPollingInterval);
    }
}
