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

    /**
     * Configuration constants
     */
    public static final int MIN_POLLING_INTERVAL = 10;
    public static final int MAX_POLLING_INTERVAL = 60;
    public static final int DEFAULT_POLLING_INTERVAL = 30;

    public static final int MIN_RETRIES = 0;
    public static final int MAX_RETRIES = 5;
    public static final int DEFAULT_RETRIES = 3;

    public static final int MIN_RETRY_DELAY_MS = 100;
    public static final int MAX_RETRY_DELAY_MS = 5000;
    public static final int DEFAULT_RETRY_DELAY_MS = 1000;

    private String ipAddress = "";
    private String deviceName = "";
    private String udn = "";
    private int pollingInterval = DEFAULT_POLLING_INTERVAL;
    private int maxRetries = DEFAULT_RETRIES;
    private int retryDelayMillis = DEFAULT_RETRY_DELAY_MS;

    /**
     * Creates a new configuration instance from a {@link Configuration} object.
     *
     * @param configuration The configuration object
     * @return A new configuration instance
     */
    public static LinkPlayConfiguration fromConfiguration(Configuration configuration) {
        LinkPlayConfiguration config = new LinkPlayConfiguration();

        // Required parameters
        config.ipAddress = (String) configuration.get("ipAddress");

        // Optional parameters with defaults
        config.deviceName = getConfigValue(configuration, "deviceName", "");
        config.udn = getConfigValue(configuration, "udn", "");
        config.pollingInterval = getConfigValue(configuration, "pollingInterval", DEFAULT_POLLING_INTERVAL);
        config.maxRetries = getConfigValue(configuration, "maxRetries", DEFAULT_RETRIES);
        config.retryDelayMillis = getConfigValue(configuration, "retryDelayMillis", DEFAULT_RETRY_DELAY_MS);

        return config;
    }

    private static <T> T getConfigValue(Configuration config, String key, T defaultValue) {
        Object value = config.get(key);
        if (value != null && defaultValue != null && defaultValue.getClass().isInstance(value)) {
            @SuppressWarnings("unchecked")
            T typedValue = (T) value;
            return typedValue;
        }
        return defaultValue;
    }

    /**
     * Validates and normalizes the configuration.
     *
     * @return true if the configuration is valid, false otherwise
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

        // Normalize values
        pollingInterval = Math.min(Math.max(pollingInterval, MIN_POLLING_INTERVAL), MAX_POLLING_INTERVAL);
        maxRetries = Math.min(Math.max(maxRetries, MIN_RETRIES), MAX_RETRIES);
        retryDelayMillis = Math.min(Math.max(retryDelayMillis, MIN_RETRY_DELAY_MS), MAX_RETRY_DELAY_MS);

        // Normalize UDN if present
        if (!udn.isEmpty() && !udn.startsWith("uuid:")) {
            udn = "uuid:" + udn;
        }

        return true;
    }

    // Getters
    public String getIpAddress() {
        return ipAddress;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getUdn() {
        return udn;
    }

    public int getPollingInterval() {
        return pollingInterval;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public int getRetryDelayMillis() {
        return retryDelayMillis;
    }

    @Override
    public String toString() {
        return String.format(
                "LinkPlayConfiguration [ipAddress=%s, deviceName=%s, udn=%s, pollingInterval=%d, maxRetries=%d, retryDelayMillis=%d]",
                ipAddress, deviceName, udn, pollingInterval, maxRetries, retryDelayMillis);
    }
}
