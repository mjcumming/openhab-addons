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

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.handler.LinkPlayThingHandler;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpClient;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
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
    private final Logger logger = LoggerFactory.getLogger(LinkPlayHandlerFactory.class);

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Set.of(THING_TYPE_DEVICE);

    private final LinkPlayHttpClient httpClient;
    private final UpnpIOService upnpIOService;

    @Activate
    public LinkPlayHandlerFactory(@Reference LinkPlayHttpClient httpClient, @Reference UpnpIOService upnpIOService) {
        this.httpClient = httpClient;
        this.upnpIOService = upnpIOService;
        logger.debug("LinkPlay Handler Factory initialized");
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        boolean supported = SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
        logger.debug("Thing type {} is{} supported", thingTypeUID, supported ? "" : " not");
        return supported;
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();
        logger.debug("Creating handler for thing type: {}", thingTypeUID);

        if (THING_TYPE_DEVICE.equals(thingTypeUID)) {
            return new LinkPlayThingHandler(thing, httpClient, upnpIOService);
        }

        logger.debug("No handler created for unsupported thing type: {}", thingTypeUID);
        return null;
    }

    @Override
    protected void removeHandler(ThingHandler thingHandler) {
        if (thingHandler instanceof LinkPlayThingHandler) {
            logger.debug("Disposing LinkPlay handler for thing {}", thingHandler.getThing().getUID());
            ((LinkPlayThingHandler) thingHandler).dispose();
        }
        super.removeHandler(thingHandler);
    }
}
