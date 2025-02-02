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
package org.openhab.binding.honeywelltcc.internal.config;

import static org.openhab.binding.honeywelltcc.internal.HoneywellTCCBindingConstants.*;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Configuration class for the Honeywell TCC bridge.
 *
 * @author Mike Cumming - Initial contribution
 */
@NonNullByDefault
public class HoneywellTCCBridgeConfiguration {
    /**
     * Username for Honeywell TCC account
     */
    public String username = "";

    /**
     * Password for Honeywell TCC account
     */
    public String password = "";

    /**
     * Refresh interval in minutes
     */
    public int refresh = 60; // Default refresh interval in minutes
}
