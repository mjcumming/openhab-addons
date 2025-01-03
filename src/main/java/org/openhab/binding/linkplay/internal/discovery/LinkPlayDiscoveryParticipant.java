
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
 * The {@link LinkPlayDiscoveryParticipant} discovers LinkPlay audio streaming devices
 * using mDNS/SSDP. It listens for LinkPlay services and creates inbox entries for discovered devices.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
@Component(service = MDNSDiscoveryParticipant.class, immediate = true)
public class LinkPlayDiscoveryParticipant implements MDNSDiscoveryParticipant {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayDiscoveryParticipant.class);
    private static final String SERVICE_TYPE = "_linkplay._tcp.local.";
    private static final int DISCOVERY_TIMEOUT_SEC = 30;
    private static final String[] PROTOCOLS = { "https", "http" };
    private static final int[] PORTS = { 443, 4443, 80 };

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
        logger.trace("Processing LinkPlay service discovery: {}", service.getName());

        if (!validateService(service)) {
            return null;
        }

        String deviceId = getDeviceId(service);
        if (deviceId == null) {
            return null;
        }

        String ipAddress = getIpAddress(service);
        if (ipAddress == null) {
            return null;
        }

        Map<String, Object> properties = new HashMap<>();

        String model = service.getPropertyString("md");
        String name = service.getName();
        String firmware = service.getPropertyString("fw");

        // Multiroom properties
        String multiroomStatus = service.getPropertyString("mr");
        String multiroomRole = determineMultiroomRole(service);
        String multiroomMaster = service.getPropertyString("ma"); // Master IP if slave
        String multiroomSlaves = service.getPropertyString("sl"); // Slave IPs if master

        logger.debug("LinkPlay device discovered - Name: {}, IP: {}, Model: {}, Role: {}", name, ipAddress, model,
                multiroomRole);

        // Add all properties
        properties.put(PROPERTY_IP_ADDRESS, ipAddress);
        properties.put(PROPERTY_DEVICE_ID, deviceId);
        properties.put(PROPERTY_MODEL, model);
        properties.put(PROPERTY_FIRMWARE, firmware);
        properties.put(PROPERTY_MULTIROOM_STATUS, multiroomStatus);
        properties.put(PROPERTY_MULTIROOM_ROLE, multiroomRole);

        if (multiroomMaster != null && !multiroomMaster.isEmpty()) {
            properties.put(PROPERTY_MULTIROOM_MASTER, multiroomMaster);
            logger.trace("Device is a slave of master: {}", multiroomMaster);
        }
        if (multiroomSlaves != null && !multiroomSlaves.isEmpty()) {
            properties.put(PROPERTY_MULTIROOM_SLAVES, multiroomSlaves);
            logger.trace("Device is a master with slaves: {}", multiroomSlaves);
        }

        ThingUID uid = new ThingUID(THING_TYPE_DEVICE, deviceId);

        logger.debug("Adding LinkPlay device to inbox: {}", uid);

        return DiscoveryResultBuilder.create(uid).withLabel(name != null ? name : "LinkPlay Device")
                .withRepresentationProperty(PROPERTY_DEVICE_ID).withProperties(properties)
                .withTimeToLive(TimeUnit.SECONDS.toSeconds(DISCOVERY_TIMEOUT_SEC)).build();
    }

    @Override
    public @Nullable ThingUID getThingUID(ServiceInfo service) {
        if (!validateService(service)) {
            return null;
        }

        String deviceId = getDeviceId(service);
        if (deviceId == null) {
            return null;
        }

        return new ThingUID(THING_TYPE_DEVICE, deviceId);
    }

    private boolean validateService(@Nullable ServiceInfo service) {
        if (service == null || !service.hasData()) {
            logger.trace("Invalid service data");
            return false;
        }
        return true;
    }

    private @Nullable String getDeviceId(ServiceInfo service) {
        String deviceId = service.getPropertyString("id");
        if (deviceId == null || deviceId.isEmpty()) {
            logger.trace("No device ID available for: {}", service.getName());
            return null;
        }
        return deviceId;
    }

    private @Nullable String getIpAddress(ServiceInfo service) {
        InetAddress[] addresses = service.getInet4Addresses();
        if (addresses == null || addresses.length == 0) {
            logger.trace("No IPv4 address available for: {}", service.getName());
            return null;
        }
        return addresses[0].getHostAddress();
    }

    private String determineMultiroomRole(ServiceInfo service) {
        String multiroomStatus = service.getPropertyString("mr");
        String masterIp = service.getPropertyString("ma");
        String slaveList = service.getPropertyString("sl");

        if (multiroomStatus == null || multiroomStatus.isEmpty()) {
            return MULTIROOM_ROLE_NONE;
        }

        if (masterIp != null && !masterIp.isEmpty()) {
            return MULTIROOM_ROLE_SLAVE;
        }

        if (slaveList != null && !slaveList.isEmpty()) {
            return MULTIROOM_ROLE_MASTER;
        }

        return MULTIROOM_ROLE_NONE;
    }

    private boolean testDeviceConnectivity(String ipAddress) {
        for (String protocol : PROTOCOLS) {
            for (int port : PORTS) {
                try {
                    logger.trace("Testing connectivity to {}:{} using {}", ipAddress, port, protocol);
                    // Implementation will test device connectivity via HTTP/HTTPS
                    return true;
                } catch (Exception e) {
                    logger.trace("Failed to connect to {}:{} using {}: {}", ipAddress, port, protocol, e.getMessage());
                }
            }
        }
        return false;
    }
}
