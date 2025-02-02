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
 * Exception thrown when a requested device cannot be found
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class HoneywellTCCDeviceNotFoundException extends HoneywellTCCException {
    private static final long serialVersionUID = 1L;

    public HoneywellTCCDeviceNotFoundException(String message) {
        super(message);
    }
}
