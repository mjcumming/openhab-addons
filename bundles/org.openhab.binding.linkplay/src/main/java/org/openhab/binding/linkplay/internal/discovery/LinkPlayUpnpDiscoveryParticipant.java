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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.Service;
import org.jupnp.model.types.ServiceId;
import org.jupnp.model.types.UDAServiceId;
import org.openhab.binding.linkplay.internal.transport.http.LinkPlayApiException;
import org.openhab.binding.linkplay.internal.transport.http.LinkPlayCommunicationException;
import org.openhab.binding.linkplay.internal.transport.http.LinkPlayHttpClient;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.upnp.UpnpDiscoveryParticipant;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
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
    private final ThingRegistry thingRegistry;

    @Activate
    public LinkPlayUpnpDiscoveryParticipant(@Reference LinkPlayHttpClient httpClient,
            @Reference ThingRegistry thingRegistry) {
        this.httpClient = httpClient;
        this.thingRegistry = thingRegistry;
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
        try {
            // First check if this looks like a LinkPlay device based on UPnP services
            if (!isLinkPlayDevice(device)) {
                logger.trace("Device {} does not have required UPnP services", device.getDetails().getFriendlyName());
                return null;
            }

            // Try multiple methods to get the IP address
            String ip = null;

            // Method 1: Try descriptor URL first (most reliable)
            if (device.getIdentity().getDescriptorURL() != null) {
                ip = device.getIdentity().getDescriptorURL().getHost();
                logger.trace("Got IP {} from descriptor URL", ip);
            }

            // Method 2: Try base URL if descriptor URL failed
            if ((ip == null || ip.isEmpty()) && device.getDetails().getBaseURL() != null) {
                ip = device.getDetails().getBaseURL().getHost();
                logger.trace("Got IP {} from base URL", ip);
            }

            if (ip == null || ip.isEmpty()) {
                logger.trace("Could not determine IP address for device {}", device.getDetails().getFriendlyName());
                return null;
            }

            String modelName = device.getDetails().getModelDetails().getModelName();
            String friendlyName = device.getDetails().getFriendlyName();

            // Add more detailed logging about the device we're checking
            logger.trace("Checking potential LinkPlay device: IP={}, Model={}, Name={}", ip, modelName, friendlyName);

            // Try to validate via HTTP with increased timeout
            try {
                CompletableFuture<String> future = httpClient.getStatusEx(ip);
                String response = future.get(5000, TimeUnit.MILLISECONDS);

                if (!response.isEmpty()) {
                    if (response.contains("uuid")) {
                        // Successfully validated as LinkPlay
                        String deviceId = device.getIdentity().getUdn().getIdentifierString();
                        // Remove 'uuid:' prefix if present and normalize format
                        deviceId = deviceId.replace("uuid:", "").replace("-", "");
                        logger.debug("Creating ThingUID with deviceId: {}", deviceId);
                        ThingUID thingUID = new ThingUID(THING_TYPE_DEVICE, deviceId);

                        // Check if this thing already exists
                        if (thingRegistry.get(thingUID) != null) {
                            logger.debug("LinkPlay device {} already exists, skipping discovery", thingUID);
                            return null;
                        }

                        logger.debug("Confirmed LinkPlay device at {}: {}", ip, response);
                        return thingUID;
                    } else {
                        logger.trace("Device at {} responded but no UUID found: {}", ip, response);
                    }
                } else {
                    logger.trace("Device at {} returned empty response", ip);
                }
            } catch (Exception e) {
                logger.trace("HTTP validation failed for device at {}: {}", ip, e.getMessage());
            }

            return null;
        } catch (Exception e) {
            logger.debug("Discovery error for device {}: {}", device.getDetails().getFriendlyName(), e.getMessage());
            return null;
        }
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

        logger.debug("Creating discovery result for ThingUID: {}", thingUID);

        // Check if this thing already exists
        if (thingRegistry.get(thingUID) != null) {
            logger.debug("LinkPlay device {} already exists, skipping discovery result", thingUID);
            return null;
        }

        // final check by calling getStatusEx via LinkPlayHttpClient
        logger.trace("DescriptorURL is '{}'", device.getIdentity().getDescriptorURL());
        String ipAddress = device.getIdentity().getDescriptorURL().getHost();
        if (ipAddress == null || ipAddress.isEmpty()) {
            return null;
        }

        if (!validateDevice(ipAddress)) {
            logger.trace("Skipping device at {} => HTTP check did not confirm LinkPlay", ipAddress);
            return null;
        }

        // Grab more details for the discovery result
        String manufacturer = device.getDetails().getManufacturerDetails().getManufacturer();
        String modelName = device.getDetails().getModelDetails().getModelName();
        String friendlyName = device.getDetails().getFriendlyName();
        String deviceUDN = device.getIdentity().getUdn().getIdentifierString();
        // Remove 'uuid:' prefix if present and normalize format
        String normalizedUDN = deviceUDN.replace("uuid:", "").replace("-", "");

        Map<String, Object> properties = new HashMap<>();

        // Add both configuration and property values to the properties map
        properties.put(CONFIG_IP_ADDRESS, ipAddress);
        properties.put(CONFIG_UDN, normalizedUDN);
        properties.put(CONFIG_DEVICE_NAME, friendlyName);
        properties.put(PROPERTY_MODEL, modelName);
        properties.put(PROPERTY_MANUFACTURER, manufacturer);
        properties.put(PROPERTY_UDN, normalizedUDN);

        String label = String.format("%s (%s)", friendlyName, ipAddress);
        logger.debug("Building discovery result for {}: label={}, properties={}", thingUID, label, properties);
        return DiscoveryResultBuilder.create(thingUID).withLabel(label).withProperties(properties)
                .withThingType(THING_TYPE_DEVICE).withRepresentationProperty(CONFIG_IP_ADDRESS)
                .withTTL(DISCOVERY_RESULT_TTL_SECONDS).build();
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

        // First check if we already have a thing with this IP
        for (Thing thing : thingRegistry.getAll()) {
            if (thing.getThingTypeUID().equals(THING_TYPE_DEVICE)) {
                Configuration config = thing.getConfiguration();
                if (ipAddress.equals(config.get(CONFIG_IP_ADDRESS))) {
                    logger.debug("Device at {} already exists as thing {}, skipping validation", ipAddress,
                            thing.getUID());
                    return false;
                }
            }
        }

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
                logger.trace("Device {} => API error: {}", ipAddress, cause.getMessage());
            } else if (cause instanceof LinkPlayCommunicationException) {
                logger.trace("Device {} => Communication error: {}", ipAddress, cause.getMessage());
            } else {
                logger.trace("Device {} => Exception during getStatusEx: {}", ipAddress, e.getMessage());
            }
        } catch (Exception e) {
            logger.trace("Device {} => Unexpected exception: {}", ipAddress, e.getMessage());
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
