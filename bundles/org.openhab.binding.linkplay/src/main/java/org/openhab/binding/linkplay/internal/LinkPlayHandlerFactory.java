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

import static org.openhab.binding.linkplay.internal.LinkPlayBindingConstants.*;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.binding.linkplay.internal.handler.LinkPlayThingHandler;
import org.openhab.binding.linkplay.internal.transport.http.LinkPlayHttpClient;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.thing.Thing;
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
 * The {@link LinkPlayHandlerFactory} is responsible for creating and
 * initializing new LinkPlay Things & ThingHandlers as requested by openHAB.
 * <p>
 * Typical flow:
 * 1) Discovery or the user defines a new Thing with IP/UDN config.
 * 2) openHAB calls createThing(...) if needed, or calls createHandler(...).
 * 3) We validate the config, build a {@link LinkPlayThingHandler}, and return it.
 * <p>
 * We maintain a map of active handlers for possible reference or disposal.
 * - This is optional but can be useful for tracking or cleanup.
 * 
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.linkplay", service = ThingHandlerFactory.class)
public class LinkPlayHandlerFactory extends BaseThingHandlerFactory {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayHandlerFactory.class);

    // Regex to ensure IP is in basic IPv4 format: x.x.x.x
    private static final Pattern IP_PATTERN = Pattern.compile("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$");
    // Regex to ensure UDN is something like "uuid:ABC123_..."
    private static final Pattern UDN_PATTERN = Pattern.compile("^uuid:[0-9a-zA-Z_-]+$");

    // We store references to each active LinkPlayThingHandler, keyed by ThingUID
    private final Map<ThingUID, LinkPlayThingHandler> handlers = new HashMap<>();

    private final UpnpIOService upnpIOService;
    private final LinkPlayHttpClient httpClient;

    /**
     * Constructor called by OSGi with references to needed services.
     */
    @Activate
    public LinkPlayHandlerFactory(@Reference UpnpIOService upnpIOService, @Reference LinkPlayHttpClient httpClient) {
        this.upnpIOService = upnpIOService;
        this.httpClient = httpClient;
    }

    /**
     * Returns 'true' if we support the given thingTypeUID, which is checked before creation.
     */
    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    /**
     * Creates the actual ThingHandler instance for a discovered or user-defined Thing.
     * This is invoked by openHAB once the Thing is known to exist (from createThing(...) or from discovery).
     */
    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        // Create handler immediately for any supported device type
        if (SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID)) {
            // Extract config from the Thing's config map
            Configuration config = thing.getConfiguration();
            LinkPlayConfiguration linkplayConfig = LinkPlayConfiguration.fromConfiguration(config);

            // Validate minimum required config (IP address)
            if (!linkplayConfig.isValid()) {
                logger.error("Invalid configuration for LinkPlay thing {} - missing IP address", thing.getUID());
                return null;
            }

            logger.debug("Creating LinkPlayThingHandler for thing '{}' with IP '{}'", thing.getUID(),
                    linkplayConfig.getIpAddress());

            try {
                // Create handler with validated config
                LinkPlayThingHandler handler = new LinkPlayThingHandler(thing, httpClient, upnpIOService,
                        linkplayConfig);
                handlers.put(thing.getUID(), handler);
                return handler;
            } catch (Exception e) {
                logger.error("Failed to create LinkPlayThingHandler for thing {} => {}", thing.getUID(),
                        e.getMessage());
                return null;
            }
        }

        return null;
    }

    /**
     * Overridden to let you create the physical Thing in code, e.g. from a manual addition or partial discovery.
     * You set default UID if none is provided, fill properties, etc.
     */
    @Override
    public @Nullable Thing createThing(ThingTypeUID thingTypeUID, Configuration configuration,
            @Nullable ThingUID thingUID, @Nullable ThingUID bridgeUID) {
        // Confirm we actually handle this type
        if (!SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID)) {
            throw new IllegalArgumentException(
                    "Thing type " + thingTypeUID + " is not supported by the linkplay binding");
        }

        // We require 'ipAddress' at a minimum
        if (!configuration.containsKey(CONFIG_IP_ADDRESS)) {
            throw new IllegalArgumentException("IP address is required for a LinkPlay device");
        }

        String ipAddress = (String) configuration.get(CONFIG_IP_ADDRESS);
        // Basic IP address validation
        if (!IP_PATTERN.matcher(ipAddress).matches()) {
            throw new IllegalArgumentException("Invalid IP address format: " + ipAddress);
        }

        // If the user didn't specify a ThingUID, build a default one using IP
        ThingUID finalThingUID = (thingUID != null) ? thingUID
                : new ThingUID(thingTypeUID, ipAddress.replace('.', '_'));

        // Prepare the property map for the new Thing
        Map<String, String> properties = new HashMap<>();
        properties.put(PROPERTY_IP, ipAddress);

        // Optionally handle UDN if present
        String udn = (String) configuration.get(CONFIG_UDN);
        if (udn != null && !udn.isEmpty()) {
            String normalizedUDN = udn.startsWith("uuid:") ? udn : "uuid:" + udn;
            if (UDN_PATTERN.matcher(normalizedUDN).matches()) {
                properties.put(PROPERTY_UDN, normalizedUDN);
                configuration.put(CONFIG_UDN, normalizedUDN);
            }
        }

        // Let the base factory create the actual Thing instance
        Thing createdThing = super.createThing(thingTypeUID, configuration, finalThingUID, bridgeUID);

        // If createdThing is not null, attach these properties
        if (createdThing != null) {
            createdThing.setProperties(properties);
        }
        return createdThing;
    }

    /**
     * Called when openHAB removes or replaces a Thing, so we can properly dispose of the handler
     * and remove it from our local map.
     */
    @Override
    protected void removeHandler(ThingHandler thingHandler) {
        if (thingHandler instanceof LinkPlayThingHandler handler) {
            ThingUID thingUID = handler.getThing().getUID();
            // Remove from local map
            handlers.remove(thingUID);

            // Dispose the handler to shut down polling, etc.
            try {
                handler.dispose();
            } catch (Exception e) {
                logger.warn("Error disposing handler for thing {} => {}", thingUID, e.getMessage());
            }
        }
    }
}
