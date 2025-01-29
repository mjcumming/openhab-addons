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
package org.openhab.binding.somecomfort.internal.client.exceptions;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The {@link APIError} class represents general API errors from the Total Comfort API
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class APIError extends SomeComfortError {
    private static final long serialVersionUID = 1L;

    public APIError(String message) {
        super(message);
    }
} 