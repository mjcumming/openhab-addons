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
package org.openhab.binding.linkplay.internal.transport.upnp;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Interface for handling UPnP events from a LinkPlay device.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public interface LinkPlayEventListener {

    /**
     * Called when a UPnP event is received from the device.
     *
     * @param service The UPnP service that generated the event (e.g., AVTransport, RenderingControl)
     * @param variable The name of the state variable that changed
     * @param value The new value of the state variable
     */
    void onEventReceived(String service, String variable, String value);
}
