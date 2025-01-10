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
package org.openhab.binding.linkplay.internal.discovery;

import static org.openhab.binding.linkplay.internal.LinkPlayBindingConstants.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.jupnp.model.meta.RemoteDevice;
import org.openhab.binding.linkplay.internal.http.LinkPlayPemConstants;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.upnp.UpnpDiscoveryParticipant;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A UPnP discovery participant for LinkPlay devices.
 * Performs additional HTTP(S) validation before confirming device discovery.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
@Component
public class LinkPlayUpnpDiscoveryParticipant implements UpnpDiscoveryParticipant {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayUpnpDiscoveryParticipant.class);

    private static final int[] HTTPS_PORTS = { 443, 4443 };
    private static final int HTTP_PORT = 80;
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 2000;

    private static final String VALIDATION_ENDPOINT = "/httpapi.asp?command=getStatusEx";
    private static final String EXPECTED_RESPONSE_CONTENT = "uuid";

    @Override
    public Set<ThingTypeUID> getSupportedThingTypeUIDs() {
        return Collections.singleton(THING_TYPE_DEVICE);
    }

    @Override
    public @Nullable ThingUID getThingUID(@Nullable RemoteDevice device) {
        if (device != null) {
            String manufacturer = device.getDetails().getManufacturerDetails().getManufacturer();
            if (SUPPORTED_MANUFACTURER.equals(manufacturer)) {
                return new ThingUID(THING_TYPE_DEVICE, device.getIdentity().getUdn().getIdentifierString());
            }
        }
        return null;
    }

    @Override
    public @Nullable DiscoveryResult createResult(RemoteDevice device) {
        if (device == null) {
            return null;
        }

        ThingUID thingUID = getThingUID(device);
        if (thingUID == null) {
            return null;
        }

        String ipAddress = device.getIdentity().getDescriptorURL().getHost();
        String friendlyName = device.getDetails().getFriendlyName();
        if (friendlyName == null || friendlyName.isEmpty()) {
            friendlyName = "LinkPlay Device";
        }

        logger.debug("UPnP: Checking LinkPlay device '{}' at IP={} for validation", friendlyName, ipAddress);

        // First validate IP address format
        if (!isValidIpAddress(ipAddress)) {
            logger.warn("UPnP: Invalid IP address {} for device {}. Skipping discovery.", ipAddress, friendlyName);
            return null;
        }

        // Then validate it's actually a LinkPlay device by checking the API endpoint
        if (!testLinkPlayHttp(ipAddress)) {
            logger.warn("UPnP: Device at IP={} not responding to LinkPlay API. Skipping discovery.", ipAddress);
            return null;
        }

        Map<String, Object> properties = new HashMap<>();
        properties.put(CONFIG_IP_ADDRESS, ipAddress);
        properties.put("udn", device.getIdentity().getUdn().getIdentifierString());
        properties.put("modelName", device.getDetails().getModelDetails().getModelName());
        properties.put("manufacturer", device.getDetails().getManufacturerDetails().getManufacturer());

        String label = friendlyName + " (" + ipAddress + ")";

        return DiscoveryResultBuilder.create(thingUID).withProperties(properties).withLabel(label)
                .withRepresentationProperty(CONFIG_IP_ADDRESS).build();
    }

    private boolean isValidIpAddress(String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty()) {
            return false;
        }

        String[] octets = ipAddress.split("\\.");
        if (octets.length != 4) {
            return false;
        }

        try {
            for (String octet : octets) {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean testLinkPlayHttp(String ipAddress) {
        // Try HTTPS ports first
        for (int port : HTTPS_PORTS) {
            if (httpCheck("https", ipAddress, port, true)) {
                return true;
            }
        }
        // Fall back to HTTP if HTTPS fails
        return httpCheck("http", ipAddress, HTTP_PORT, false);
    }

    private boolean httpCheck(String protocol, String ipAddress, int port, boolean useSsl) {
        String urlStr = String.format("%s://%s:%d%s", protocol, ipAddress, port, VALIDATION_ENDPOINT);
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn;
            if (useSsl) {
                conn = (HttpsURLConnection) url.openConnection();
                ((HttpsURLConnection) conn)
                        .setSSLSocketFactory(LinkPlayPemConstants.createSslContext().getSocketFactory());
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String responseLine;
                    while ((responseLine = reader.readLine()) != null) {
                        if (responseLine.contains(EXPECTED_RESPONSE_CONTENT)) {
                            logger.debug("HTTP(S) check success => {} contains expected content", urlStr);
                            return true;
                        }
                    }
                }
            } else {
                logger.trace("HTTP(S) check => {} => responseCode={}", urlStr, responseCode);
            }
        } catch (Exception e) {
            logger.trace("HTTP(S) check fail => {} => {}", urlStr, e.getMessage());
        }
        return false;
    }
}
