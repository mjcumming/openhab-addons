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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.Service;
import org.jupnp.model.types.ServiceId;
import org.jupnp.model.types.UDAServiceId;
import org.openhab.binding.linkplay.internal.http.LinkPlayApiException;
import org.openhab.binding.linkplay.internal.http.LinkPlayCommunicationException;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpClient;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.upnp.UpnpDiscoveryParticipant;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A UPnP discovery participant for LinkPlay devices. We discover all
 * "MediaRenderer" or "MediaServer" devices, then do an HTTP check
 * using our LinkPlayHttpClient to confirm it's truly LinkPlay.
 * 
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
@Component(service = UpnpDiscoveryParticipant.class, configurationPid = "discovery.linkplay")
public class LinkPlayUpnpDiscoveryParticipant implements UpnpDiscoveryParticipant {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayUpnpDiscoveryParticipant.class);

    // We only look at these UPnP device types
    // private static final Set<String> SUPPORTED_DEVICE_TYPES = Set.of("urn:schemas-upnp-org:device:MediaRenderer:1",
    // "urn:schemas-upnp-org:device:MediaServer:1");

    private static final int DISCOVERY_RESULT_TTL_SECONDS = 300;

    // LinkPlay devices normally have these UPnP services
    private static final ServiceId SERVICE_ID_AV_TRANSPORT = new UDAServiceId("AVTransport");
    private static final ServiceId SERVICE_ID_RENDERING_CONTROL = new UDAServiceId("RenderingControl");

    private final LinkPlayHttpClient httpClient;

    @Activate
    public LinkPlayUpnpDiscoveryParticipant(@Reference LinkPlayHttpClient httpClient) {
        this.httpClient = httpClient;
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

        // final check by calling getStatusEx via LinkPlayHttpClient
        logger.trace("DescriptorURL is '{}'", device.getIdentity().getDescriptorURL());
        String ipAddress = device.getIdentity().getDescriptorURL().getHost();
        if (ipAddress == null || ipAddress.isEmpty()) {
            return null;
        }

        if (!validateDevice(ipAddress)) {
            logger.debug("Skipping device at {} => HTTP check did not confirm LinkPlay", ipAddress);
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
        logger.trace("isLinkPlayDevice => deviceType={}", deviceType);

        // Must be at least one of these device types
        // if (!SUPPORTED_DEVICE_TYPES.contains(deviceType)) {
        // return false;
        // }
        String type = device.getType().getType().trim().toLowerCase();
        if (!(type.contains("mediarenderer") || type.contains("mediaserver"))) {
            logger.trace("isLinkPlayDevice => deviceType={} does not contain 'mediarenderer' or 'mediaserver'",
                    deviceType);
            return false;
        }

        // Check for required services
        Service<?, ?> avTransportService = device.findService(SERVICE_ID_AV_TRANSPORT);
        Service<?, ?> renderingControlService = device.findService(SERVICE_ID_RENDERING_CONTROL);
        boolean hasRequiredServices = avTransportService != null && renderingControlService != null;

        logger.trace("isLinkPlayDevice => Checking deviceType={}, avTransport={}, renderingControl={}", deviceType,
                avTransportService != null, renderingControlService != null);
        logger.trace("isLinkPlayDevice => returns {}", hasRequiredServices);

        logger.debug("Device {} => AVTransport={}, RenderingControl={}", device.getDetails().getFriendlyName(),
                avTransportService != null, renderingControlService != null);

        return hasRequiredServices;
    }

    /**
     * Reuses the LinkPlayHttpClient to call getStatusEx(...) and see
     * if the response contains "uuid".
     */
    private boolean validateDevice(String ipAddress) {
        logger.trace("validateDevice => Checking IP={}", ipAddress);
        try {
            String response = httpClient.getStatusEx(ipAddress).get();
            logger.info("LinkPlay device found at {} => getStatusEx response: '{}'", ipAddress, response);

            if (response.contains("uuid")) {
                logger.debug("LinkPlay device confirmed at {} => 'uuid' found in getStatusEx", ipAddress);
                return true;
            }
            logger.trace("No 'uuid' in response => not recognized as LinkPlay");
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof LinkPlayApiException) {
                logger.warn("Device {} => API error: {}", ipAddress, cause.getMessage());
            } else if (cause instanceof LinkPlayCommunicationException) {
                logger.warn("Device {} => Communication error: {}", ipAddress, cause.getMessage());
            } else {
                logger.warn("Device {} => Exception during getStatusEx: {}", ipAddress, e.getMessage());
            }
        } catch (Exception e) {
            logger.warn("Device {} => Unexpected exception: {}", ipAddress, e.getMessage());
        }
        logger.trace("validateDevice => returning false for IP={}", ipAddress);
        return false;
    }

    @Activate
    protected void activate(ComponentContext context) {
        logger.debug("LinkPlay UPnP Discovery service activated");
    }

    protected void deactivate() {
        logger.debug("LinkPlay UPnP Discovery deactivated");
    }
}
