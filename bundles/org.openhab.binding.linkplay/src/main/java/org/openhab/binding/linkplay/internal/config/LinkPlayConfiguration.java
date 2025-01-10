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

/**
 * The {@link LinkPlayConfiguration} class contains fields mapping thing configuration parameters.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayConfiguration {

    /**
     * Minimum refresh interval in seconds
     */
    public static final int MIN_REFRESH_INTERVAL = 10;

    /**
     * IP address of the LinkPlay device
     */
    public String ipAddress = "";

    /**
     * Name of the LinkPlay device
     */
    public String deviceName = "";

    /**
     * Polling interval in seconds for polling the device state.
     * Default value is 30 seconds.
     */
    public int pollingInterval = 30;

    public static final int MIN_POLLING_INTERVAL = 10;
    public static final int MAX_POLLING_INTERVAL = 60;

    public boolean isValid() {
        if (ipAddress.isEmpty()) {
            return false;
        }

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

        pollingInterval = Math.min(Math.max(pollingInterval, MIN_POLLING_INTERVAL), MAX_POLLING_INTERVAL);
        return true;
    }
}
