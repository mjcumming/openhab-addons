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
package org.openhab.binding.honeywelltcc.internal;

import static org.openhab.binding.honeywelltcc.internal.HoneywellTCCBindingConstants.*;

import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.honeywelltcc.internal.client.HoneywellTCCHttpClient;
import org.openhab.binding.honeywelltcc.internal.handler.HoneywellTCCBridgeHandler;
import org.openhab.binding.honeywelltcc.internal.handler.HoneywellTCCThermostatHandler;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.thing.Bridge;
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
 * The {@link HoneywellTCCHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.honeywelltcc", service = ThingHandlerFactory.class)
public class HoneywellTCCHandlerFactory extends BaseThingHandlerFactory {

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Set.of(THING_TYPE_ACCOUNT,
            THING_TYPE_THERMOSTAT);

    private final Logger logger = LoggerFactory.getLogger(HoneywellTCCHandlerFactory.class);
    private final HttpClientFactory httpClientFactory;
    private final ScheduledExecutorService scheduler;
    private final ThingRegistry thingRegistry;

    @Activate
    public HoneywellTCCHandlerFactory(@Reference HttpClientFactory httpClientFactory,
            @Reference ScheduledExecutorService scheduler, @Reference ThingRegistry thingRegistry) {
        this.httpClientFactory = httpClientFactory;
        this.scheduler = scheduler;
        this.thingRegistry = thingRegistry;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (THING_TYPE_ACCOUNT.equals(thingTypeUID)) {
            return new HoneywellTCCBridgeHandler((Bridge) thing, scheduler);
        } else if (THING_TYPE_THERMOSTAT.equals(thingTypeUID)) {
            ThingUID bridgeUID = thing.getBridgeUID();
            if (bridgeUID == null) {
                logger.warn("Thermostat {} does not have an associated bridge UID", thing.getUID());
                return null;
            }
            Thing bridgeThing = thingRegistry.get(bridgeUID);
            if (bridgeThing == null || !(bridgeThing instanceof Bridge)) {
                logger.warn("No valid bridge found for thermostat {} with bridge UID {}", thing.getUID(), bridgeUID);
                return null;
            }
            Bridge bridge = (Bridge) bridgeThing;
            if (bridge.getHandler() == null || !(bridge.getHandler() instanceof HoneywellTCCBridgeHandler)) {
                logger.warn("Bridge handler is invalid for thermostat {}", thing.getUID());
                return null;
            }
            HoneywellTCCHttpClient client = ((HoneywellTCCBridgeHandler) bridge.getHandler()).getClient();
            if (client == null) {
                logger.warn("HTTP client is null for thermostat {}", thing.getUID());
                return null;
            }
            return new HoneywellTCCThermostatHandler(thing, bridge, client, scheduler);
        }

        return null;
    }
}
