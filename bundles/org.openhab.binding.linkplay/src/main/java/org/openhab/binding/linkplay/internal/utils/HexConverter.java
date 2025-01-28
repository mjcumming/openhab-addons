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
 * This class is used to decode hexadecimal strings received from LinkPlay devices into readable text.
 * It handles various edge cases such as empty strings, non-hex input, and ensures only printable ASCII
 * characters are included in the output.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class HexConverter {

    /**
     * Converts a hex string to its UTF-8 string representation.
     * This method is designed to be robust and handle various input cases:
     * - Empty strings return empty string
     * - The string "Unknown" is returned as-is
     * - Invalid hex strings (odd length or non-hex chars) return the original input
     * - Only printable ASCII characters (32-126) are included in the output
     *
     * Example:
     * Input: "556E6B6E6F776E" -> Output: "Unknown"
     * Input: "48656C6C6F" -> Output: "Hello"
     *
     * @param hex The hex string to convert, must not be null (enforced by @NonNullByDefault)
     * @return The decoded ASCII text, or the original string if conversion fails
     */
    public static String hexToString(String hex) {
        // Handle empty input
        if (hex.isEmpty()) {
            return "";
        }

        // Special case: already in decoded form
        if ("Unknown".equals(hex)) {
            return hex;
        }

        // Normalize input by removing whitespace
        hex = hex.replaceAll("\\s", "");

        // Validate hex string format
        if (hex.length() % 2 != 0 || !hex.matches("[0-9A-Fa-f]+")) {
            return hex; // Return original if not valid hex
        }

        try {
            StringBuilder output = new StringBuilder();
            // Process hex string two characters at a time
            for (int i = 0; i < hex.length(); i += 2) {
                String str = hex.substring(i, i + 2);
                int value = Integer.parseInt(str, 16);
                // Filter for printable ASCII range (32-126)
                if (value >= 32 && value <= 126) {
                    output.append((char) value);
                }
            }
            String result = output.toString();
            return result.isEmpty() ? hex : result;
        } catch (Exception e) {
            // Return original string if any parsing error occurs
            return hex;
        }
    }
}
