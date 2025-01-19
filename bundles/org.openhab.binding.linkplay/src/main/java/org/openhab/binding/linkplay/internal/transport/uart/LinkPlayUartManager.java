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
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.LinkPlayDeviceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LinkPlayUartManager} handles UART communication with LinkPlay devices.
 * This is a placeholder for future UART support.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayUartManager {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayUartManager.class);
    private final LinkPlayDeviceManager deviceManager;
    private @Nullable LinkPlayUartClient uartClient;

    public LinkPlayUartManager(LinkPlayDeviceManager deviceManager) {
        this.deviceManager = deviceManager;
        String host = deviceManager.getConfig().getIpAddress();
        this.uartClient = new LinkPlayUartClient(host, this);
    }

    /**
     * Initialize UART communication
     */
    public void initialize() {
        logger.debug("[{}] Initializing UART manager", deviceManager.getDeviceState().getDeviceName());
        final @Nullable LinkPlayUartClient client = uartClient;
        if (client != null) {
            client.open();
        }
    }

    /**
     * Clean up resources
     */
    public void dispose() {
        logger.debug("[{}] Disposing UART manager", deviceManager.getDeviceState().getDeviceName());
        final @Nullable LinkPlayUartClient client = uartClient;
        if (client != null) {
            client.close();
        }
        uartClient = null;
    }
}
