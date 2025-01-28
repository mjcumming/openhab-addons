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
import org.openhab.core.config.core.Configuration;

/**
 * Tests for {@link LinkPlayConfiguration}
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayConfigurationTest {

    @Test
    public void testValidConfiguration() {
        Configuration config = new Configuration();
        config.put("ipAddress", "192.168.1.100");
        config.put("deviceName", "TestDevice");
        config.put("playerStatusPollingInterval", 5);
        config.put("deviceStatusPollingInterval", 10);

        LinkPlayConfiguration deviceConfig = LinkPlayConfiguration.fromConfiguration(config);
        assertTrue(deviceConfig.isValid());
        assertEquals("192.168.1.100", deviceConfig.getIpAddress());
        assertEquals("TestDevice", deviceConfig.getDeviceName());
        assertEquals(5, deviceConfig.getPlayerStatusPollingInterval());
        assertEquals(10, deviceConfig.getDeviceStatusPollingInterval());
    }

    @Test
    public void testInvalidIpAddress() {
        Configuration config = new Configuration();
        config.put("ipAddress", "invalid.ip");

        LinkPlayConfiguration deviceConfig = LinkPlayConfiguration.fromConfiguration(config);
        assertFalse(deviceConfig.isValid());
    }

    @Test
    public void testEmptyConfiguration() {
        Configuration config = new Configuration();
        LinkPlayConfiguration deviceConfig = LinkPlayConfiguration.fromConfiguration(config);
        assertFalse(deviceConfig.isValid());
    }
} 