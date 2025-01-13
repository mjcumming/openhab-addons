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
import javax.net.ssl.SSLContext;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.Service;
import org.jupnp.model.types.ServiceId;
import org.jupnp.model.types.UDAServiceId;
import org.openhab.binding.linkplay.internal.http.LinkPlaySslUtil;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.upnp.UpnpDiscoveryParticipant;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
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
@Component(service = UpnpDiscoveryParticipant.class, configurationPid = "discovery.linkplay")
public class LinkPlayUpnpDiscoveryParticipant implements UpnpDiscoveryParticipant {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayUpnpDiscoveryParticipant.class);

    private static final int DISCOVERY_RESULT_TTL_SECONDS = 300;
    private static final Set<String> SUPPORTED_DEVICE_TYPES = Set.of(UPNP_DEVICE_TYPE,
            "urn:schemas-upnp-org:device:MediaServer:1" // Some LinkPlay devices use this type
    );
    private static final Set<String> SUPPORTED_MANUFACTURERS = Set.of(UPNP_MANUFACTURER.toLowerCase(),
            "linkplay technology inc.");

    // Additional identifiers for LinkPlay devices
    private static final String LINKPLAY_IDENTIFIER = "linkplay";
    private static final String LINKPLAY_MODEL_PREFIX = "ls"; // Common prefix for LinkPlay models

    // HTTP/HTTPS validation settings
    private static final int[] HTTPS_PORTS = { 443, 4443 };
    private static final int HTTP_PORT = 80;
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 2000;
    private static final String VALIDATION_ENDPOINT = "/httpapi.asp?command=getStatusEx";
    private static final String EXPECTED_RESPONSE_CONTENT = "uuid";

    private static final ServiceId SERVICE_ID_AV_TRANSPORT = new UDAServiceId("AVTransport");
    private static final ServiceId SERVICE_ID_RENDERING_CONTROL = new UDAServiceId("RenderingControl");

    private static final String PROPERTY_HAS_AVTRANSPORT = "hasAVTransport";
    private static final String PROPERTY_HAS_RENDERING_CONTROL = "hasRenderingControl";

    @Activate
    protected void activate(ComponentContext context) {
        logger.debug("LinkPlay UPnP Discovery service activated");
    }

    @Override
    public Set<ThingTypeUID> getSupportedThingTypeUIDs() {
        return Collections.singleton(THING_TYPE_DEVICE);
    }

    @Override
    public @Nullable ThingUID getThingUID(RemoteDevice device) {
        String manufacturer = device.getDetails().getManufacturerDetails().getManufacturer();
        String deviceType = device.getType().getType();
        String modelName = device.getDetails().getModelDetails().getModelName();
        String modelDesc = device.getDetails().getModelDetails().getModelDescription();
        String deviceUDN = device.getIdentity().getUdn().getIdentifierString();

        // Log device details at trace level for debugging
        logger.trace("Discovered UPnP device - Manufacturer: {}, Type: {}, Model: {}, Desc: {}, UDN: {}", manufacturer,
                deviceType, modelName, modelDesc, deviceUDN);

        if (!isLinkPlayDevice(device)) {
            return null;
        }

        // Create thing UID from UDN, ensuring it's properly formatted
        String normalizedUDN = deviceUDN.startsWith("uuid:") ? deviceUDN : "uuid:" + deviceUDN;
        logger.debug("Found LinkPlay device - Manufacturer: {}, Model: {}, UDN: {}", manufacturer, modelName,
                normalizedUDN);

        return new ThingUID(THING_TYPE_DEVICE, normalizedUDN.replaceAll("[^a-zA-Z0-9_]", ""));
    }

    @Override
    public @Nullable DiscoveryResult createResult(RemoteDevice device) {
        ThingUID thingUID = getThingUID(device);
        if (thingUID == null) {
            return null;
        }

        String ipAddress = device.getIdentity().getDescriptorURL().getHost();
        if (ipAddress == null || ipAddress.isEmpty() || !validateDevice(ipAddress)) {
            logger.debug("Device validation failed for IP {}", ipAddress);
            return null;
        }

        // Get device details
        String manufacturer = device.getDetails().getManufacturerDetails().getManufacturer();
        String modelName = device.getDetails().getModelDetails().getModelName();
        String friendlyName = device.getDetails().getFriendlyName();
        String deviceUDN = device.getIdentity().getUdn().getIdentifierString();

        // Normalize UDN
        String normalizedUDN = deviceUDN.startsWith("uuid:") ? deviceUDN : "uuid:" + deviceUDN;

        // Create discovery result with all necessary properties
        Map<String, Object> properties = new HashMap<>();
        properties.put(CONFIG_IP_ADDRESS, ipAddress);
        properties.put(CONFIG_UDN, normalizedUDN);
        properties.put(PROPERTY_MODEL, modelName);
        properties.put(PROPERTY_MANUFACTURER, manufacturer);
        properties.put(PROPERTY_UDN, normalizedUDN);

        String label = String.format("%s (%s)", friendlyName, ipAddress);
        return DiscoveryResultBuilder.create(thingUID).withLabel(label).withProperties(properties)
                .withRepresentationProperty(PROPERTY_UDN).withTTL(DISCOVERY_RESULT_TTL_SECONDS).build();
    }

    private boolean isLinkPlayDevice(RemoteDevice device) {
        String manufacturer = device.getDetails().getManufacturerDetails().getManufacturer();
        String deviceType = device.getType().getType();
        String modelName = device.getDetails().getModelDetails().getModelName();
        String modelDesc = device.getDetails().getModelDetails().getModelDescription();

        // Check UPnP services
        Service<?, ?> avTransportService = device.findService(SERVICE_ID_AV_TRANSPORT);
        Service<?, ?> renderingControlService = device.findService(SERVICE_ID_RENDERING_CONTROL);
        boolean hasRequiredServices = avTransportService != null && renderingControlService != null;

        // Log service availability
        logger.debug("Device {} services - AVTransport: {}, RenderingControl: {}",
                device.getDetails().getFriendlyName(), avTransportService != null, renderingControlService != null);

        // Primary check: manufacturer and device type
        boolean isValidManufacturer = manufacturer != null
                && SUPPORTED_MANUFACTURERS.contains(manufacturer.toLowerCase());
        boolean isValidDeviceType = deviceType != null && SUPPORTED_DEVICE_TYPES.contains(deviceType);

        // Secondary check: Look for LinkPlay identifiers in model name and description
        boolean hasLinkPlayIdentifier = false;
        if (modelName != null) {
            hasLinkPlayIdentifier = modelName.toLowerCase().contains(LINKPLAY_IDENTIFIER)
                    || modelName.toLowerCase().startsWith(LINKPLAY_MODEL_PREFIX);
        }
        if (!hasLinkPlayIdentifier && modelDesc != null) {
            hasLinkPlayIdentifier = modelDesc.toLowerCase().contains(LINKPLAY_IDENTIFIER);
        }

        // Device is valid if it matches manufacturer/type OR contains LinkPlay identifier OR has required services
        return (isValidManufacturer && isValidDeviceType) || hasLinkPlayIdentifier || hasRequiredServices;
    }

    private boolean validateDevice(String ipAddress) {
        // Try HTTPS first
        for (int port : HTTPS_PORTS) {
            if (validateConnection(ipAddress, port, true)) {
                logger.debug("HTTPS validation successful for {} on port {}", ipAddress, port);
                return true;
            }
        }

        // Fall back to HTTP
        boolean result = validateConnection(ipAddress, HTTP_PORT, false);
        if (result) {
            logger.debug("HTTP validation successful for {} on port {}", ipAddress, HTTP_PORT);
        } else {
            logger.debug("All validation attempts failed for {}", ipAddress);
        }
        return result;
    }

    private boolean validateConnection(String ipAddress, int port, boolean useSsl) {
        String protocol = useSsl ? "https" : "http";
        String urlStr = String.format("%s://%s:%d%s", protocol, ipAddress, port, VALIDATION_ENDPOINT);

        try {
            HttpURLConnection conn = createConnection(urlStr, useSsl);
            int responseCode = conn.getResponseCode();

            if (responseCode == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String responseLine;
                    while ((responseLine = reader.readLine()) != null) {
                        if (responseLine.contains(EXPECTED_RESPONSE_CONTENT)) {
                            logger.trace("HTTP(S) validation successful: {} contains expected content", urlStr);
                            return true;
                        }
                    }
                }
                logger.trace("HTTP(S) validation failed: {} response missing expected content", urlStr);
            } else {
                logger.trace("HTTP(S) validation failed: {} returned status code {}", urlStr, responseCode);
            }
        } catch (Exception e) {
            logger.trace("HTTP(S) validation error for {}: {}", urlStr, e.getMessage());
        }
        return false;
    }

    private HttpURLConnection createConnection(String urlStr, boolean useSsl) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn;

        if (useSsl) {
            HttpsURLConnection httpsConn = (HttpsURLConnection) url.openConnection();
            SSLContext sslContext = LinkPlaySslUtil.createSslContext(LinkPlaySslUtil.createTrustAllManager());
            httpsConn.setSSLSocketFactory(sslContext.getSocketFactory());
            httpsConn.setHostnameVerifier((hostname, session) -> true);
            conn = httpsConn;
        } else {
            conn = (HttpURLConnection) url.openConnection();
        }

        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("GET");

        return conn;
    }

    protected void deactivate() {
        logger.debug("LinkPlay UPnP Discovery deactivated");
    }
}
