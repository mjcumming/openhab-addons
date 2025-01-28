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
package org.openhab.binding.linkplay.internal.config;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LinkPlayConfigurationException}
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayConfigurationExceptionTest {

    @Test
    public void testExceptionMessage() {
        String message = "Test error message";
        LinkPlayConfigurationException exception = new LinkPlayConfigurationException(message);
        assertEquals(message, exception.getMessage());
    }

    @Test
    public void testExceptionWithCause() {
        String message = "Test error message";
        Throwable cause = new IllegalArgumentException("Cause message");
        LinkPlayConfigurationException exception = new LinkPlayConfigurationException(message, cause);
        assertEquals(message, exception.getMessage());
        assertEquals(cause, exception.getCause());
    }
} 