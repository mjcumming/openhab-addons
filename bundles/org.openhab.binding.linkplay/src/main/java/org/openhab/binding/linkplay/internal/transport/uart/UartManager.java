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
import org.openhab.binding.linkplay.internal.DeviceManager;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link UartManager} handles UART communication with LinkPlay devices.
 * This is a placeholder for future UART support.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class UartManager {

    private static final Logger logger = LoggerFactory.getLogger(UartManager.class);
    private final DeviceManager deviceManager;
    private @Nullable UartClient uartClient;

    public UartManager(DeviceManager deviceManager) {
        this.deviceManager = deviceManager;
        LinkPlayConfiguration config = deviceManager.getConfig();
        String host = config.getIpAddress();
        this.uartClient = new UartClient(host, this);
    }

    /**
     * Initialize UART communication
     */
    public void initialize() {
        logger.debug("[{}] Initializing UART manager", deviceManager.getDeviceState().getDeviceName());
        final @Nullable UartClient client = uartClient;
        if (client != null) {
            client.open();
        }
    }

    /**
     * Clean up resources
     */
    public void dispose() {
        logger.debug("[{}] Disposing UART manager", deviceManager.getDeviceState().getDeviceName());
        final @Nullable UartClient client = uartClient;
        if (client != null) {
            client.close();
        }
        uartClient = null;
    }
}
