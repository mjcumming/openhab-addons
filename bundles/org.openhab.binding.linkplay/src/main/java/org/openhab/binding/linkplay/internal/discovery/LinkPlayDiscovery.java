/*******************************************************************************
 * Copyright (c) 2010-2025 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.openhab.binding.linkplay.internal.discovery;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.jupnp.model.meta.ModelDetails;
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
 * A UPnP discovery participant for LinkPlay devices, modeled on the Sonos approach,
 * with an additional HTTP(S) check before placing the device in the inbox.
 *
 * <p>
 * openHAB 4 automatically calls this for new or updated UPnP devices. If we recognize
 * the device as LinkPlay, we do an HTTP(S) test to confirm it's responsive.
 * If validated, we build and return a DiscoveryResult; otherwise, we return null.
 */
@NonNullByDefault
@Component
public class LinkPlayUpnpDiscoveryParticipant implements UpnpDiscoveryParticipant {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayUpnpDiscoveryParticipant.class);

    private static final ThingTypeUID LINKPLAY_SPEAKER_TYPE_UID = new ThingTypeUID("linkplay", "speaker");

    private static final String LINKPLAY_MANUFACTURER = "LinkPlay";
    private static final String LINKPLAY_UPNP_DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";

    private static final int[] HTTPS_PORTS = { 443, 4443 };
    private static final int HTTP_PORT = 80;
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 2000;

    private static final String VALIDATION_ENDPOINT = "/httpapi.asp?command=getStatusEx";
    private static final String EXPECTED_RESPONSE_CONTENT = "uuid"; // Example key to confirm LinkPlay device

    @Override
    public Set<ThingTypeUID> getSupportedThingTypeUIDs() {
        return Set.of(LINKPLAY_SPEAKER_TYPE_UID);
    }

    @Override
    public @Nullable ThingUID getThingUID(RemoteDevice device) {
        if (device == null) {
            logger.trace("UPnP getThingUID called with null device");
            return null;
        }

        String manufacturer = device.getDetails().getManufacturerDetails().getManufacturer();
        ModelDetails modelDetails = device.getDetails().getModelDetails();
        String modelName = (modelDetails != null) ? modelDetails.getModelName() : "";
        String upnpDeviceType = device.getType().getType();

        boolean isLinkPlay = false;
        if (manufacturer != null && manufacturer.toLowerCase().contains(LINKPLAY_MANUFACTURER.toLowerCase())) {
            isLinkPlay = true;
        } else if (upnpDeviceType.equalsIgnoreCase(LINKPLAY_UPNP_DEVICE_TYPE)
                || modelName.toLowerCase().contains("linkplay")) {
            isLinkPlay = true;
        }

        if (!isLinkPlay) {
            logger.trace("UPnP device not recognized as LinkPlay => Mfr='{}', Type='{}', Model='{}'", manufacturer,
                    upnpDeviceType, modelName);
            return null;
        }

        String udn = device.getIdentity().getUdn().getIdentifierString();
        if (udn == null || udn.isBlank()) {
            logger.trace("UPnP LinkPlay device has no UDN => cannot build ThingUID");
            return null;
        }

        logger.debug("UPnP recognized LinkPlay device => manufacturer='{}', UDN='{}'", manufacturer, udn);
        return new ThingUID(LINKPLAY_SPEAKER_TYPE_UID, udn.replaceAll("[^a-zA-Z0-9_\\-]", ""));
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

        logger.debug("UPnP: Checking LinkPlay device '{}' at IP={} for final validation", friendlyName, ipAddress);

        if (!testLinkPlayHttp(ipAddress)) {
            logger.warn("UPnP: LinkPlay device at IP={} not responding to HTTP(S). Skipping discovery result.",
                    ipAddress);
            return null;
        }

        Map<String, Object> properties = new HashMap<>();
        properties.put("ipAddress", ipAddress);
        properties.put("udn", device.getIdentity().getUdn().getIdentifierString());
        properties.put("modelName", device.getDetails().getModelDetails().getModelName());
        properties.put("manufacturer", device.getDetails().getManufacturerDetails().getManufacturer());

        String label = friendlyName + " (" + ipAddress + ")";

        DiscoveryResult result = DiscoveryResultBuilder.create(thingUID).withProperties(properties).withLabel(label)
                .withRepresentationProperty("ipAddress").build();

        logger.debug("UPnP: Created DiscoveryResult for LinkPlay => {}", result);
        return result;
    }

    private boolean testLinkPlayHttp(String ipAddress) {
        for (int port : HTTPS_PORTS) {
            if (httpCheck("https", ipAddress, port, true)) {
                return true;
            }
        }
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
