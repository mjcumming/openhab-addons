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
package org.openhab.binding.honeywelltcc.internal.handler;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.openhab.binding.honeywelltcc.internal.HoneywellTCCBindingConstants;
import org.openhab.binding.honeywelltcc.internal.client.HoneywellTCCClient;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory responsible for creating Honeywell Total Comfort Control handlers.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.honeywelltcc", service = ThingHandlerFactory.class)
public class HoneywellTCCHandlerFactory extends BaseThingHandlerFactory {
    private final Logger logger = LoggerFactory.getLogger(HoneywellTCCHandlerFactory.class);
    private final Map<String, HoneywellTCCClient> clientCache = new HashMap<>();
    private final ScheduledExecutorService scheduler;

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(
            HoneywellTCCBindingConstants.THING_TYPE_BRIDGE,
            HoneywellTCCBindingConstants.THING_TYPE_THERMOSTAT);

    @Activate
    public HoneywellTCCHandlerFactory(
            @Reference(target = "(threadPool=honeywelltcc)") ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();
        logger.debug("Creating handler for thing type: {}", thingTypeUID);

        if (HoneywellTCCBindingConstants.THING_TYPE_BRIDGE.equals(thingTypeUID)) {
            return new HoneywellTCCBridgeHandler((Bridge) thing, scheduler, this::getClient, () -> removeClient(thing.getUID().getId()));
        }

        if (HoneywellTCCBindingConstants.THING_TYPE_THERMOSTAT.equals(thingTypeUID)) {
            return new HoneywellTCCHandler(thing);
        }

        return null;
    }

    @Override
    protected void removeHandler(ThingHandler thingHandler) {
        if (thingHandler instanceof HoneywellTCCBridgeHandler) {
            HoneywellTCCBridgeHandler bridgeHandler = (HoneywellTCCBridgeHandler) thingHandler;
            removeClient(bridgeHandler.getBridgeId());
        }
        super.removeHandler(thingHandler);
    }

    private synchronized HoneywellTCCClient getClient(String bridgeId, String username, String password) {
        return clientCache.computeIfAbsent(bridgeId, k -> {
            logger.debug("Creating new client for bridge {}", bridgeId);
            return new HoneywellTCCClient(username, password);
        });
    }

    private synchronized void removeClient(String bridgeId) {
        HoneywellTCCClient client = clientCache.remove(bridgeId);
        if (client != null) {
            logger.debug("Removing client for bridge {}", bridgeId);
            client.close();
        }
    }
} 