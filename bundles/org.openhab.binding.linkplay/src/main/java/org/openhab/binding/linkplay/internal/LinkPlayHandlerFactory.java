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
package org.openhab.binding.linkplay.internal;

import static org.openhab.binding.linkplay.internal.BindingConstants.*;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.binding.linkplay.internal.handler.LinkPlayThingHandler;
import org.openhab.binding.linkplay.internal.transport.http.LinkPlayHttpClient;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LinkPlayHandlerFactory} is responsible for creating things and thing
 * handlers for the LinkPlay binding.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.linkplay", service = ThingHandlerFactory.class)
public class LinkPlayHandlerFactory extends BaseThingHandlerFactory {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayHandlerFactory.class);

    private final Map<ThingUID, ThingHandler> handlers = new HashMap<>();

    private final UpnpIOService upnpIOService;
    private final LinkPlayHttpClient httpClient;

    @Reference
    private @NonNullByDefault({}) ThingRegistry thingRegistry;

    @Activate
    public LinkPlayHandlerFactory(@Reference UpnpIOService upnpIOService, @Reference LinkPlayHttpClient httpClient) {
        this.upnpIOService = upnpIOService;
        this.httpClient = httpClient;
    }

    public @Nullable ThingHandler getHandlerByIP(String ipAddress) {
        for (Thing thing : thingRegistry.getAll()) {
            if (THING_TYPE_MEDIASTREAMER.equals(thing.getThingTypeUID())) {
                Object configIp = thing.getConfiguration().get(CONFIG_IP_ADDRESS);
                if (configIp instanceof String thingIP && ipAddress.equals(thingIP)) {
                    return thing.getHandler();
                }
            }
        }
        logger.debug("No LinkPlay handler found for IP: {}", ipAddress);
        return null;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID)) {
            synchronized (handlers) {
                if (handlers.containsKey(thing.getUID())) {
                    logger.debug("Handler already exists for Thing: {}", thing.getUID());
                    return handlers.get(thing.getUID());
                }

                try {
                    LinkPlayConfiguration config = LinkPlayConfiguration.fromConfiguration(thing.getConfiguration());

                    if (!config.isValid()) {
                        restoreConfigurationFromProperties(thing);
                        config = LinkPlayConfiguration.fromConfiguration(thing.getConfiguration());
                        if (!config.isValid()) {
                            throw new IllegalArgumentException("Invalid configuration for Thing: " + thing.getUID());
                        }
                    }

                    // Store IP in properties
                    thing.setProperty(PROPERTY_IP, config.getIpAddress());

                    logger.debug("Creating LinkPlayThingHandler for Thing '{}' with IP '{}' and UDN '{}'",
                            thing.getUID(), config.getIpAddress(), config.getUdn());

                    ThingHandler handler = new LinkPlayThingHandler(thing, httpClient, upnpIOService, config,
                            thingRegistry);
                    handlers.put(thing.getUID(), handler);
                    return handler;
                } catch (Exception e) {
                    logger.error("Failed to create handler for Thing {}: {}", thing.getUID(), e.getMessage(), e);
                }
            }
        }
        return null;
    }

    private void restoreConfigurationFromProperties(Thing thing) {
        // Create new map to avoid modifying the original properties
        Map<String, String> properties = new HashMap<>(thing.getProperties());
        if (properties.isEmpty()) {
            return;
        }

        Configuration configuration = thing.getConfiguration();

        String ipAddress = properties.get(PROPERTY_IP);
        if (ipAddress != null && !ipAddress.isEmpty()) {
            configuration.put(CONFIG_IP_ADDRESS, ipAddress);
        }

        String udn = properties.get(PROPERTY_UDN);
        if (udn != null && !udn.isEmpty()) {
            configuration.put(CONFIG_UDN, udn);
        }
    }

    @Override
    protected void removeHandler(ThingHandler thingHandler) {
        if (thingHandler instanceof ThingHandler handler) {
            ThingUID thingUID = handler.getThing().getUID();
            synchronized (handlers) {
                handlers.remove(thingUID);
            }

            try {
                handler.dispose();
            } catch (Exception e) {
                logger.warn("Error disposing handler for Thing {}: {}", thingUID, e.getMessage());
            }
        }
    }

    @Override
    public @Nullable Thing createThing(ThingTypeUID thingTypeUID, Configuration configuration,
            @Nullable ThingUID thingUID, @Nullable ThingUID bridgeUID) {
        if (SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID)) {
            // For now we just create the Thing, later we might want to add more properties
            return super.createThing(thingTypeUID, configuration, thingUID, bridgeUID);
        }
        return null;
    }
}
