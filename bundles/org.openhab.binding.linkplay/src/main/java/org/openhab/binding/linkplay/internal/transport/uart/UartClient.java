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
package org.openhab.binding.linkplay.internal.transport.uart;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Low-level UART client for LinkPlay devices.
 * This is a placeholder for future UART support.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class UartClient {
    private static final Logger logger = LoggerFactory.getLogger(UartClient.class);
    private final String host;

    public UartClient(String host, UartManager uartManager) {
        this.host = host;
        logger.debug("UART client created for host {}", host);
    }

    /**
     * Open connection placeholder
     */
    public void open() {
        logger.debug("UART open placeholder for {}", host);
    }

    /**
     * Close connection placeholder
     */
    public void close() {
        logger.debug("UART close placeholder for {}", host);
    }

    /**
     * Send command placeholder
     */
    public void sendCommand(String command) {
        logger.debug("UART send command placeholder for {}: {}", host, command);
    }
}
