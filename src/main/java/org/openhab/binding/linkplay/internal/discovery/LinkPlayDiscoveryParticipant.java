
/**
 * Copyright (c) 2010-2025 Contributors to the openHAB project
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
package org.openhab.binding.linkplay.internal.discovery;

import static org.openhab.binding.linkplay.internal.LinkPlayBindingConstants.*;

import java.net.InetAddress;
import java.util.Set;

import javax.jmdns.ServiceInfo;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.mdns.MDNSDiscoveryParticipant;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LinkPlayDiscoveryParticipant} is responsible for discovering LinkPlay devices through mDNS.
 *
 * @author Mark Theunissen - Initial contribution
 */
@NonNullByDefault
@Component(service = MDNSDiscoveryParticipant.class)
public class LinkPlayDiscoveryParticipant implements MDNSDiscoveryParticipant {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayDiscoveryParticipant.class);
    private static final String SERVICE_TYPE = "_linkplay._tcp.local.";

    @Override
    public Set<ThingTypeUID> getSupportedThingTypeUIDs() {
        return SUPPORTED_THING_TYPES_UIDS;
    }

    @Override
    public String getServiceType() {
        return SERVICE_TYPE;
    }

    @Override
    public @Nullable DiscoveryResult createResult(ServiceInfo service) {
        if (!service.hasData()) {
            return null;
        }

        // Get device ID first
        String deviceId = service.getPropertyString("id");
        if (deviceId == null || deviceId.isEmpty()) {
            logger.debug("LinkPlay device found but no device ID available: {}", service.getName());
            return null;
        }

        // Get IP address
        InetAddress[] ipAddresses = service.getInet4Addresses();
        if (ipAddresses.length == 0) {
            logger.debug("LinkPlay device found but no IPv4 address available: {}", service.getName());
            return null;
        }

        String ipAddress = ipAddresses[0].getHostAddress();
        
        logger.debug("LinkPlay mDNS discovery found device: {}", service.getName());
        logger.debug("IP Address: {}", ipAddress);
        logger.debug("Device ID: {}", deviceId);

        ThingUID uid = new ThingUID(THING_TYPE_DEVICE, deviceId);

        return DiscoveryResultBuilder.create(uid)
                .withLabel(service.getName())
                .withRepresentationProperty(PROPERTY_DEVICE_ID)
                .withProperty(PROPERTY_IP_ADDRESS, ipAddress)
                .withProperty(PROPERTY_DEVICE_ID, deviceId)
                .build();
    }

    @Override
    public @Nullable ThingUID getThingUID(ServiceInfo service) {
        if (!service.hasData()) {
            return null;
        }

        String deviceId = service.getPropertyString("id");
        if (deviceId == null || deviceId.isEmpty()) {
            return null;
        }

        return new ThingUID(THING_TYPE_DEVICE, deviceId);
    }
}
