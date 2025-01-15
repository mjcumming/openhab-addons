/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0
 * which is available at http://www.eclipse.org/legal/epl-2.0
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
 * A UPnP discovery participant for LinkPlay devices. We discover all
 * "MediaRenderer" or "MediaServer" devices, then do an HTTP check to confirm.
 * <p>
 * This approach follows openHAB best practices:
 * - Listen for broad UPnP announcements
 * - Filter by basic device type/services
 * - Perform additional HTTP fingerprint check (getStatusEx) to ensure it's LinkPlay
 * 
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
@Component(service = UpnpDiscoveryParticipant.class, configurationPid = "discovery.linkplay")
public class LinkPlayUpnpDiscoveryParticipant implements UpnpDiscoveryParticipant {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayUpnpDiscoveryParticipant.class);

    // We only look at these UPnP device types
    private static final Set<String> SUPPORTED_DEVICE_TYPES = Set.of("urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:device:MediaServer:1");

    private static final int DISCOVERY_RESULT_TTL_SECONDS = 300;

    // HTTP/HTTPS validation to confirm it is truly a LinkPlay device
    private static final int[] HTTPS_PORTS = { 443, 4443 };
    private static final int HTTP_PORT = 80;
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 2000;

    // The endpoint we call to see if "uuid" text is returned
    private static final String VALIDATION_ENDPOINT = "/httpapi.asp?command=getStatusEx";
    private static final String EXPECTED_RESPONSE_CONTENT = "uuid";

    // LinkPlay devices normally have these UPnP services
    private static final ServiceId SERVICE_ID_AV_TRANSPORT = new UDAServiceId("AVTransport");
    private static final ServiceId SERVICE_ID_RENDERING_CONTROL = new UDAServiceId("RenderingControl");

    @Activate
    protected void activate(ComponentContext context) {
        logger.debug("LinkPlay UPnP Discovery service activated");
    }

    @Override
    public Set<ThingTypeUID> getSupportedThingTypeUIDs() {
        // We only declare the "device" type from your binding
        return Collections.singleton(THING_TYPE_DEVICE);
    }

    /**
     * Attempts to derive a ThingUID if this device is recognized
     * as LinkPlay based on basic checks (UPnP services, etc).
     */
    @Override
    public @Nullable ThingUID getThingUID(RemoteDevice device) {
        String manufacturer = device.getDetails().getManufacturerDetails().getManufacturer();
        String deviceType = device.getType().getType();
        String modelName = device.getDetails().getModelDetails().getModelName();
        String modelDesc = device.getDetails().getModelDetails().getModelDescription();
        String deviceUDN = device.getIdentity().getUdn().getIdentifierString();

        logger.trace("UPnP device discovered => manufacturer={}, type={}, model={}, desc={}, UDN={}", manufacturer,
                deviceType, modelName, modelDesc, deviceUDN);

        // If we cannot confirm it's a LinkPlay or MediaRenderer device, return null
        if (!isLinkPlayDevice(device)) {
            return null;
        }

        // Create a stable ThingUID from the device's UDN
        String normalizedUDN = deviceUDN.startsWith("uuid:") ? deviceUDN : "uuid:" + deviceUDN;
        normalizedUDN = normalizedUDN.replaceAll("[^a-zA-Z0-9_]", ""); // clean out special chars

        logger.debug("Found possible LinkPlay device => Mfr={}, Model={}, UDN={}", manufacturer, modelName,
                normalizedUDN);
        return new ThingUID(THING_TYPE_DEVICE, normalizedUDN);
    }

    /**
     * Once we have a ThingUID, we do a final HTTP check to confirm this
     * is truly a LinkPlay device, then create a DiscoveryResult.
     */
    @Override
    public @Nullable DiscoveryResult createResult(RemoteDevice device) {
        ThingUID thingUID = getThingUID(device);
        if (thingUID == null) {
            return null; // not recognized
        }

        // Final check by calling getStatusEx
        String ipAddress = device.getIdentity().getDescriptorURL().getHost();
        if (ipAddress == null || ipAddress.isEmpty()) {
            return null;
        }

        // If HTTP check fails, skip creating a discovery result
        if (!validateDevice(ipAddress)) {
            logger.debug("Skipping device at {} => validation (HTTP check) did not confirm LinkPlay", ipAddress);
            return null;
        }

        // Grab more details for the discovery result
        String manufacturer = device.getDetails().getManufacturerDetails().getManufacturer();
        String modelName = device.getDetails().getModelDetails().getModelName();
        String friendlyName = device.getDetails().getFriendlyName();
        String deviceUDN = device.getIdentity().getUdn().getIdentifierString();
        String normalizedUDN = deviceUDN.startsWith("uuid:") ? deviceUDN : "uuid:" + deviceUDN;

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

    /**
     * Basic filter to see if the device is *likely* LinkPlay. We accept devices
     * with certain UPnP types (MediaRenderer or MediaServer) and known services (AVTransport, RenderingControl).
     * The real check is the HTTP call in validateDevice(...).
     */
    private boolean isLinkPlayDevice(RemoteDevice device) {
        String deviceType = device.getType().getType();

        // Must be at least one of these device types
        if (!SUPPORTED_DEVICE_TYPES.contains(deviceType)) {
            return false;
        }

        // Also require presence of AVTransport & RenderingControl
        Service<?, ?> avTransportService = device.findService(SERVICE_ID_AV_TRANSPORT);
        Service<?, ?> renderingControlService = device.findService(SERVICE_ID_RENDERING_CONTROL);
        boolean hasRequiredServices = avTransportService != null && renderingControlService != null;
        logger.debug("Device {} => AVTransport={}, RenderingControl={}", device.getDetails().getFriendlyName(),
                avTransportService != null, renderingControlService != null);

        return hasRequiredServices;
    }

    /**
     * Attempt connecting over HTTPS first, then fallback to HTTP to confirm
     * that getStatusEx returns "uuid". This ensures the device is truly LinkPlay.
     */
    private boolean validateDevice(String ipAddress) {
        // Try HTTPS first
        for (int port : HTTPS_PORTS) {
            if (validateConnection(ipAddress, port, true)) {
                logger.debug("LinkPlay device confirmed at {} (HTTPS, port={})", ipAddress, port);
                return true;
            }
        }

        // Then fallback to HTTP
        if (validateConnection(ipAddress, HTTP_PORT, false)) {
            logger.debug("LinkPlay device confirmed at {} (HTTP, port={})", ipAddress, HTTP_PORT);
            return true;
        }
        return false;
    }

    /**
     * Opens either HTTP or HTTPS connection to the given IP & port, calls getStatusEx,
     * and checks if the body contains "uuid".
     */
    private boolean validateConnection(String ipAddress, int port, boolean useSsl) {
        String protocol = useSsl ? "https" : "http";
        String urlStr = String.format("%s://%s:%d%s", protocol, ipAddress, port, VALIDATION_ENDPOINT);

        try {
            HttpURLConnection conn = createConnection(urlStr, useSsl);
            int responseCode = conn.getResponseCode();

            if (responseCode == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains(EXPECTED_RESPONSE_CONTENT)) {
                            logger.trace("HTTP check success => '{}' contains '{}'", urlStr, EXPECTED_RESPONSE_CONTENT);
                            return true;
                        }
                    }
                }
                logger.trace("HTTP check => '{}' does not contain '{}'", urlStr, EXPECTED_RESPONSE_CONTENT);
            } else {
                logger.trace("HTTP check => '{}' returned status code {}", urlStr, responseCode);
            }
        } catch (Exception e) {
            logger.trace("HTTP check => '{}' error: {}", urlStr, e.getMessage());
        }
        return false;
    }

    /**
     * Creates either an HttpsURLConnection or HttpURLConnection, setting
     * timeouts, SSLContext, etc. as needed.
     */
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
