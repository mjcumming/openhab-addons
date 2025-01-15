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
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpClient;
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
 * The {@link LinkPlayHandlerFactory} is responsible for creating things and thing handlers.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.linkplay", service = ThingHandlerFactory.class)
public class LinkPlayHandlerFactory extends BaseThingHandlerFactory {

    private static final Pattern IP_PATTERN = Pattern.compile("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$");
    private static final Pattern UDN_PATTERN = Pattern.compile("^uuid:[0-9a-zA-Z_-]+$");

    private final Logger logger = LoggerFactory.getLogger(LinkPlayHandlerFactory.class);
    private final Map<ThingUID, LinkPlayThingHandler> handlers = new HashMap<>();
    private final UpnpIOService upnpIOService;
    private final LinkPlayHttpClient httpClient;

    @Activate
    public LinkPlayHandlerFactory(final @Reference UpnpIOService upnpIOService,
            final @Reference LinkPlayHttpClient httpClient) {
        this.upnpIOService = upnpIOService;
        this.httpClient = httpClient;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (!SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID)) {
            logger.debug("Unsupported thing type {} - cannot create handler", thingTypeUID);
            return null;
        }

        // Pull the config from the Thing
        Configuration config = thing.getConfiguration();
        // Convert to LinkPlayConfiguration, which does IP/UDN checks, etc.
        LinkPlayConfiguration linkplayConfig = LinkPlayConfiguration.fromConfiguration(config);

        if (!linkplayConfig.isValid()) {
            logger.error("Invalid configuration for thing {}", thing.getUID());
            return null;
        }

        logger.debug("Creating LinkPlay handler for thing '{}' with UDN '{}' at IP {}", thing.getUID(),
                linkplayConfig.getUdn(), linkplayConfig.getIpAddress());

        try {
            // Pass the validated config object into the handler
            LinkPlayThingHandler handler = new LinkPlayThingHandler(thing, upnpIOService, httpClient, linkplayConfig);
            handlers.put(thing.getUID(), handler);
            return handler;
        } catch (Exception e) {
            logger.error("Handler creation failed for thing {}: {}", thing.getUID(), e.getMessage());
            return null;
        }
    }

    @Override
    public @Nullable Thing createThing(ThingTypeUID thingTypeUID, Configuration configuration,
            @Nullable ThingUID thingUID, @Nullable ThingUID bridgeUID) {
        if (!SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID)) {
            throw new IllegalArgumentException(
                    String.format("Thing type %s is not supported by the linkplay binding", thingTypeUID));
        }

        // Validate basic config
        if (!configuration.containsKey(CONFIG_IP_ADDRESS)) {
            throw new IllegalArgumentException("IP address is required");
        }

        String ipAddress = (String) configuration.get(CONFIG_IP_ADDRESS);
        if (!IP_PATTERN.matcher(ipAddress).matches()) {
            throw new IllegalArgumentException("Invalid IP address format: " + ipAddress);
        }

        // Create a default UID if none is provided
        ThingUID deviceUID = thingUID != null ? thingUID : new ThingUID(thingTypeUID, ipAddress.replace('.', '_'));

        // Add IP to the thing properties
        Map<String, String> properties = new HashMap<>();
        properties.put(PROPERTY_IP, ipAddress);

        // Handle UDN if present
        String udn = (String) configuration.get(CONFIG_UDN);
        if (udn != null && !udn.isEmpty()) {
            String normalizedUDN = udn.startsWith("uuid:") ? udn : "uuid:" + udn;
            if (UDN_PATTERN.matcher(normalizedUDN).matches()) {
                properties.put(PROPERTY_UDN, normalizedUDN);
                configuration.put(CONFIG_UDN, normalizedUDN);
            }
        }

        Thing thing = super.createThing(thingTypeUID, configuration, deviceUID, bridgeUID);
        if (thing != null) {
            thing.setProperties(properties);
        }
        return thing;
    }

    @Override
    protected void removeHandler(ThingHandler thingHandler) {
        if (thingHandler instanceof LinkPlayThingHandler handler) {
            ThingUID thingUID = handler.getThing().getUID();
            handlers.remove(thingUID);
            try {
                handler.dispose();
            } catch (Exception e) {
                logger.warn("Error disposing handler for thing {}: {}", thingUID, e.getMessage());
            }
        }
    }
}
