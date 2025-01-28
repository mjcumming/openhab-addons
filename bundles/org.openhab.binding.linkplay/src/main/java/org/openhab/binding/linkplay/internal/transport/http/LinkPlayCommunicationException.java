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
package org.openhab.binding.linkplay.internal.transport.http;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Exception thrown when there is a communication error with the LinkPlay device.
 * This can occur in situations such as:
 * <ul>
 * <li>Network connectivity issues</li>
 * <li>Device timeout</li>
 * <li>Invalid HTTP responses</li>
 * <li>Connection refused by device</li>
 * </ul>
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayCommunicationException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message the detail message describing the cause of the exception
     */
    public LinkPlayCommunicationException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the specified detail message and cause.
     *
     * @param message the detail message describing the cause of the exception
     * @param cause the underlying cause of the exception
     */
    public LinkPlayCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
