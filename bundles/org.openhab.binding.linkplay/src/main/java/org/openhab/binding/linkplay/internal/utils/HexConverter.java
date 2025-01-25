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
package org.openhab.binding.linkplay.internal.utils;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The {@link HexConverter} provides utility methods for hex-to-ASCII conversion.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class HexConverter {

    /**
     * Converts a hex string to its UTF-8 string representation.
     * Handles null input, empty strings, and invalid hex values.
     *
     * @param hex The hex string to convert (e.g. "556E6B6E6F776E" for "Unknown")
     * @return The decoded ASCII text, or the original string if not valid hex
     */
    public static String hexToString(String hex) {
        if (hex.isEmpty()) {
            return "";
        }

        // If already "Unknown", no need to try decoding
        if ("Unknown".equals(hex)) {
            return hex;
        }

        // Remove any whitespace
        hex = hex.replaceAll("\\s", "");

        // Check if it's a valid hex string (must be even length and contain only hex chars)
        if (hex.length() % 2 != 0 || !hex.matches("[0-9A-Fa-f]+")) {
            return hex; // Return original if not valid hex
        }

        try {
            StringBuilder output = new StringBuilder();
            for (int i = 0; i < hex.length(); i += 2) {
                String str = hex.substring(i, i + 2);
                int value = Integer.parseInt(str, 16);
                // Only include printable ASCII characters
                if (value >= 32 && value <= 126) {
                    output.append((char) value);
                }
            }
            String result = output.toString();
            return result.isEmpty() ? hex : result;
        } catch (Exception e) {
            return hex; // Return original string if decoding fails
        }
    }
}
