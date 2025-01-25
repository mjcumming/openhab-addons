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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.Service;
import org.jupnp.model.types.ServiceId;
import org.jupnp.model.types.UDAServiceId;
import org.openhab.binding.linkplay.internal.BindingConstants;
import org.openhab.binding.linkplay.internal.transport.http.CommandResult;
import org.openhab.binding.linkplay.internal.transport.http.LinkPlayHttpClient;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.upnp.UpnpDiscoveryParticipant;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

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
        return BindingConstants.SUPPORTED_THING_TYPES_UIDS;
    }

    @Override
    public @Nullable ThingUID getThingUID(RemoteDevice device) {
        try {
            // First check if this looks like a LinkPlay device based on UPnP services
            if (!hasRequiredServices(device)) {
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
                CommandResult result = httpClient.sendRequest(ip, "getStatusEx").get(5000, TimeUnit.MILLISECONDS);

                if (result.isSuccess() && result.isJsonContent()) {
                    JsonObject deviceStatus = result.getResponse();
                    if (deviceStatus != null && deviceStatus.has("uuid")) {
                        // Successfully validated as LinkPlay
                        String deviceId = device.getIdentity().getUdn().getIdentifierString();
                        // Remove 'uuid:' prefix if present and normalize format
                        deviceId = deviceId.replace("uuid:", "").replace("-", "");
                        logger.debug("Creating ThingUID with deviceId: {}", deviceId);
                        ThingUID thingUID = new ThingUID(BindingConstants.THING_TYPE_MEDIASTREAMER, deviceId);

                        // Check if this thing already exists
                        if (thingRegistry.get(thingUID) != null) {
                            logger.debug("LinkPlay device {} already exists, skipping discovery", thingUID);
                            return null;
                        }

                        logger.debug("Confirmed LinkPlay device at {}: {}", ip, deviceStatus);
                        return thingUID;
                    }
                }
                logger.trace("Device at {} not recognized as LinkPlay", ip);
            } catch (Exception e) {
                logger.trace("HTTP validation failed for device at {}: {}", ip, e.getMessage());
            }

            return null;
        } catch (Exception e) {
            logger.debug("Discovery error for device {}: {}", device.getDetails().getFriendlyName(), e.getMessage());
            return null;
        }
    }

    @Override
    public @Nullable DiscoveryResult createResult(RemoteDevice device) {
        ThingUID thingUID = getThingUID(device);
        if (thingUID == null) {
            return null;
        }

        // Get device details from UPnP
        String ipAddress = device.getIdentity().getDescriptorURL().getHost();
        String friendlyName = device.getDetails().getFriendlyName();
        String manufacturer = device.getDetails().getManufacturerDetails().getManufacturer();
        String modelName = device.getDetails().getModelDetails().getModelName();
        String deviceUDN = device.getIdentity().getUdn().getIdentifierString();
        // Remove 'uuid:' prefix if present and normalize format
        String normalizedUDN = deviceUDN.replace("uuid:", "").replace("-", "");

        Map<String, Object> properties = new HashMap<>();
        properties.put(BindingConstants.CONFIG_IP_ADDRESS, ipAddress);
        properties.put(BindingConstants.CONFIG_UDN, normalizedUDN);
        properties.put(BindingConstants.CONFIG_DEVICE_NAME, friendlyName);
        properties.put(BindingConstants.PROPERTY_MODEL, modelName);
        properties.put(BindingConstants.PROPERTY_MANUFACTURER, manufacturer);
        properties.put(BindingConstants.PROPERTY_UDN, normalizedUDN);

        String label = String.format("%s (%s)", friendlyName, ipAddress);
        logger.debug("Building discovery result for {}: label={}, properties={}", thingUID, label, properties);

        return DiscoveryResultBuilder.create(thingUID).withLabel(label).withProperties(properties)
                .withRepresentationProperty(BindingConstants.CONFIG_UDN).withTTL(DISCOVERY_RESULT_TTL_SECONDS).build();
    }

    private boolean hasRequiredServices(@Nullable RemoteDevice device) {
        if (device == null) {
            return false;
        }

        Service<?, ?> avTransportService = device.findService(SERVICE_ID_AV_TRANSPORT);
        Service<?, ?> renderingControlService = device.findService(SERVICE_ID_RENDERING_CONTROL);

        boolean hasRequiredServices = (avTransportService != null && renderingControlService != null);
        logger.trace("Device {} has required services: AVTransport={}, RenderingControl={}",
                device.getDetails().getFriendlyName(), avTransportService != null, renderingControlService != null);

        return hasRequiredServices;
    }
}
