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
package org.openhab.binding.linkplay.internal.uart;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Listener interface for LinkPlay UART responses.
 * Implementations will receive raw UART messages from the device.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public interface LinkPlayUartResponseListener {

    /**
     * Called when a raw UART message is received from the device.
     *
     * @param rawText The raw text received from the UART connection
     */
    void onUartResponse(String rawText);
}
