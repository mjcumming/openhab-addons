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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.config.core.Configuration;

/**
 * Configuration class for the LinkPlay binding.
 * Follows OpenHAB configuration patterns for thing configuration.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayConfiguration {

    // We keep a default, but no min or max
    private static final int DEFAULT_POLLING_INTERVAL = 10;

    private String ipAddress = "";
    private String deviceName = "";
    private String udn = "";
    private int pollingInterval = DEFAULT_POLLING_INTERVAL; // 0 => disabled

    /**
     * Creates a new configuration instance from a {@link Configuration} object.
     *
     * @param configuration The configuration object
     * @return A new configuration instance
     */
    public static LinkPlayConfiguration fromConfiguration(Configuration configuration) {
        LinkPlayConfiguration config = new LinkPlayConfiguration();

        // ipAddress is required
        config.ipAddress = getString(configuration, "ipAddress", "");

        // Optional parameters with defaults
        config.deviceName = getString(configuration, "deviceName", "");
        config.udn = getString(configuration, "udn", "");

        // pollingInterval can be 0 or more (no upper limit)
        config.pollingInterval = getInteger(configuration, "pollingInterval", DEFAULT_POLLING_INTERVAL);
        if (config.pollingInterval < 0) {
            config.pollingInterval = 0; // clamp negative to 0
        }

        return config;
    }

    /**
     * Validates the IP address and normalizes the UDN if present.
     *
     * @return true if config is valid, false otherwise
     */
    public boolean isValid() {
        // IP address is required
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

        // Normalize UDN if present
        if (!udn.isEmpty() && !udn.startsWith("uuid:")) {
            udn = "uuid:" + udn;
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
     * @return The user-specified polling interval in seconds (0 = disabled).
     */
    public int getPollingInterval() {
        return pollingInterval;
    }

    @Override
    public String toString() {
        return String.format("LinkPlayConfiguration [ipAddress=%s, deviceName=%s, udn=%s, pollingInterval=%d]",
                ipAddress, deviceName, udn, pollingInterval);
    }

    // ---- Helper methods ----
    private static String getString(Configuration config, String key, String defaultVal) {
        Object val = config.get(key);
        if (val instanceof String) {
            return (String) val;
        }
        return defaultVal;
    }

    private static int getInteger(Configuration config, String key, int defaultVal) {
        Object val = config.get(key);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        return defaultVal;
    }
}
