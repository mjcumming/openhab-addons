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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.handler.LinkPlayThingHandler;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpClient;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.ComponentContext;
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

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayHandlerFactory.class);

    private final HttpClientFactory httpClientFactory;
    private final UpnpIOService upnpIOService;
    private final LinkPlayHttpClient httpClient;

    @Activate
    public LinkPlayHandlerFactory(@Reference HttpClientFactory httpClientFactory,
            @Reference UpnpIOService upnpIOService) {
        this.httpClientFactory = httpClientFactory;
        this.upnpIOService = upnpIOService;
        this.httpClient = new LinkPlayHttpClient(httpClientFactory.getCommonHttpClient());
        logger.debug("LinkPlayHandlerFactory constructed with httpClientFactory and upnpIOService");
    }

    @Override
    protected void activate(ComponentContext componentContext) {
        super.activate(componentContext);
        logger.debug("LinkPlayHandlerFactory activated with component context");
    }

    @Override
    public @Nullable Thing createThing(ThingTypeUID thingTypeUID, Configuration configuration,
            @Nullable ThingUID thingUID, @Nullable ThingUID bridgeUID) {
        if (THING_TYPE_DEVICE.equals(thingTypeUID)) {
            String ipAddress = (String) configuration.get("ipAddress");
            if (ipAddress != null) {
                ThingUID linkplayDeviceUID = thingUID != null ? thingUID
                        : new ThingUID(thingTypeUID, ipAddress.replace('.', '_'));
                logger.debug("Creating a LinkPlay thing with ID '{}' for IP '{}'", linkplayDeviceUID, ipAddress);
                return super.createThing(thingTypeUID, configuration, linkplayDeviceUID, null);
            }
        }
        return null;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        boolean supported = THING_TYPE_DEVICE.equals(thingTypeUID);
        logger.debug("Checking support for thing type {}: {}", thingTypeUID, supported);
        return supported;
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();
        logger.debug("[{}] Creating handler for thing type {}", thing.getUID(), thingTypeUID);

        if (THING_TYPE_DEVICE.equals(thingTypeUID)) {
            logger.debug("[{}] Creating LinkPlayThingHandler with configuration: {}", thing.getUID(),
                    thing.getConfiguration());
            return new LinkPlayThingHandler(thing, httpClient, upnpIOService);
        }

        logger.debug("[{}] No handler created - unsupported thing type {}", thing.getUID(), thingTypeUID);
        return null;
    }

    @Override
    protected void deactivate(ComponentContext componentContext) {
        logger.debug("LinkPlayHandlerFactory deactivated");
        super.deactivate(componentContext);
    }
}
