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
package org.openhab.binding.linkplay.internal.http;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Exception thrown when there is a communication error with the LinkPlay device
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayCommunicationException extends Exception {

    private static final long serialVersionUID = 1L;

    public LinkPlayCommunicationException(String message) {
        super(message);
    }

    public LinkPlayCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
