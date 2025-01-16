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

import java.nio.charset.StandardCharsets;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link HexConverter} provides utility methods for hex-to-ASCII conversion.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class HexConverter {

    private static final Logger logger = LoggerFactory.getLogger(HexConverter.class);

    /**
     * Converts a hex string to its UTF-8 string representation.
     * Handles null input, empty strings, and invalid hex values.
     *
     * @param hex The hex string to convert (may be null).
     * @return The UTF-8 string representation, or empty string if conversion fails.
     */
    public static String hexToString(@Nullable String hex) {
        if (hex == null || hex.isEmpty()) {
            return "";
        }

        // Remove any whitespace and validate hex string
        hex = hex.replaceAll("\\s", "");
        if (hex.length() % 2 != 0 || !hex.matches("[0-9A-Fa-f]+")) {
            logger.trace("Invalid hex string: {}", hex);
            return "";
        }

        try {
            byte[] bytes = new byte[hex.length() / 2];
            for (int i = 0; i < hex.length(); i += 2) {
                bytes[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.trace("Failed to convert hex to string: {}", e.getMessage());
            return "";
        }
    }
}
