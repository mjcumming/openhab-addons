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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link HexConverter} provides utility methods for hex-to-ASCII conversion.
 *
 * @author Michael Cumming
 */
public class HexConverter {

    private static final Logger logger = LoggerFactory.getLogger(HexConverter.class);

    /**
     * Converts a hex string to its ASCII representation.
     *
     * @param hex The hex string to convert.
     * @return The ASCII representation.
     */
    public static String hexToAscii(String hex) {
        StringBuilder ascii = new StringBuilder();
        try {
            for (int i = 0; i < hex.length(); i += 2) {
                String hexByte = hex.substring(i, i + 2);
                ascii.append((char) Integer.parseInt(hexByte, 16));
            }
        } catch (Exception e) {
            logger.warn("Failed to convert hex to ASCII: {}", e.getMessage(), e);
        }
        return ascii.toString();
    }
}
