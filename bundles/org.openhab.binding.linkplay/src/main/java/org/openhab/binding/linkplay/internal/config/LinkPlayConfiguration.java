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
 * Configuration class for LinkPlay devices.
 * Holds configuration parameters for network settings and polling intervals.
 * The configuration can be initialized from Thing properties if not directly configured.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayConfiguration {

    /**
     * Default polling intervals in seconds
     */
    public static final int DEFAULT_PLAYER_STATUS_POLLING_INTERVAL = 5;
    public static final int DEFAULT_DEVICE_STATUS_POLLING_INTERVAL = 10;

    /**
     * IP address of the device. Required for communication.
     * Can be automatically discovered via UPnP or manually configured.
     */
    private String ipAddress = "";

    /**
     * Optional device name for logging and identification.
     */
    private String deviceName = "";

    /**
     * UPnP Unique Device Name. Optional - will be discovered if not provided.
     */
    private String udn = "";

    /**
     * Polling interval for player status (playback, volume, etc.) in seconds.
     * Default: 5 seconds
     */
    private int playerStatusPollingInterval = DEFAULT_PLAYER_STATUS_POLLING_INTERVAL;

    /**
     * Polling interval for device status (network, system info) in seconds.
     * Default: 10 seconds
     */
    private int deviceStatusPollingInterval = DEFAULT_DEVICE_STATUS_POLLING_INTERVAL;

    /**
     * Creates a new configuration instance from a {@link Configuration} object.
     * Used during Thing initialization and configuration updates.
     *
     * @param config The configuration object from the Thing handler
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

    /**
     * Validates the configuration.
     * Only IP address is required as it's essential for device communication.
     * Other fields are optional and will be discovered or use defaults.
     *
     * @return true if config has required fields, false otherwise
     */
    public boolean isValid() {
        return !ipAddress.isEmpty() && isValidIpAddress(ipAddress);
    }

    /**
     * Helper method to validate IP address format.
     */
    private static boolean isValidIpAddress(String ip) {
        if (ip.isEmpty()) {
            return false;
        }

        String[] octets = ip.split("\\.");
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

    public int getPlayerStatusPollingInterval() {
        return playerStatusPollingInterval;
    }

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
