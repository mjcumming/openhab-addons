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
 * Custom exception class for handling LinkPlay API-specific errors.
 * This exception is thrown when:
 * <ul>
 * <li>The LinkPlay device returns an error response</li>
 * <li>The API call fails due to invalid parameters or state</li>
 * <li>Communication with the device fails in an API-specific way</li>
 * </ul>
 * 
 * This exception should be caught and handled by the {@link HttpManager} or higher-level components
 * to provide appropriate error handling and user feedback.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayApiException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message the detail message describing the API error
     */
    public LinkPlayApiException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the specified detail message and cause.
     *
     * @param message the detail message describing the API error
     * @param cause the underlying cause of the API error
     */
    public LinkPlayApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
