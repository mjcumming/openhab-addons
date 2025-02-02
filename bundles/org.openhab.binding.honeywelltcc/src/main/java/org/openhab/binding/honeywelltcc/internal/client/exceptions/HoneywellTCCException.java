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
package org.openhab.binding.honeywelltcc.internal.client.exceptions;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Base exception for Honeywell TCC binding
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class HoneywellTCCException extends Exception {
    private static final long serialVersionUID = 1L;

    public static final String ERROR_INVALID_CREDENTIALS = "Invalid username or password";
    public static final String ERROR_SESSION_TIMEOUT = "Session has timed out";
    public static final String ERROR_RATE_LIMIT = "Rate limit exceeded";
    public static final String ERROR_API_REJECTED = "API rejected thermostat settings";
    public static final String ERROR_UNEXPECTED_RESPONSE = "Unexpected %d response from API: %s";
    public static final String ERROR_INVALID_JSON = "Invalid response: not a JSON object";
    public static final String ERROR_UNEXPECTED_CONTENT = "Unexpected response type: %s";

    public HoneywellTCCException(String message) {
        super(message);
    }

    public HoneywellTCCException(String message, int statusCode) {
        super(message + " (Status code: " + statusCode + ")");
    }

    public HoneywellTCCException(String message, Throwable cause) {
        super(message, cause);
    }

    public HoneywellTCCException(String message, int statusCode, Throwable cause) {
        super(message + " (Status code: " + statusCode + ")", cause);
    }
}
