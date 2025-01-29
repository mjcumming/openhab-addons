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
package org.openhab.binding.honeywelltcc.internal.discovery;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.binding.honeywelltcc.internal.HoneywellTCCBindingConstants;
import org.openhab.binding.honeywelltcc.internal.handler.HoneywellTCCBridgeHandler;
import org.openhab.binding.honeywelltcc.internal.client.model.Location;
import org.openhab.binding.honeywelltcc.internal.client.model.Device;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link HoneywellTCCDiscoveryService} discovers Honeywell thermostats connected to a Total Comfort account
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class HoneywellTCCDiscoveryService extends AbstractDiscoveryService implements ThingHandlerService {
    private static final int DISCOVERY_TIMEOUT_SECONDS = 10;

    private final Logger logger = LoggerFactory.getLogger(HoneywellTCCDiscoveryService.class);
    private @Nullable HoneywellTCCBridgeHandler bridgeHandler;

    /**
     * Creates a new discovery service for Honeywell thermostats
     */
    public HoneywellTCCDiscoveryService() {
        super(Set.of(HoneywellTCCBindingConstants.THING_TYPE_THERMOSTAT), DISCOVERY_TIMEOUT_SECONDS);
    }

    @Override
    public void setThingHandler(ThingHandler handler) {
        if (handler instanceof HoneywellTCCBridgeHandler) {
            bridgeHandler = (HoneywellTCCBridgeHandler) handler;
            ((HoneywellTCCBridgeHandler) handler).setDiscoveryService(this);
        }
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return bridgeHandler;
    }

    @Override
    protected void startScan() {
        logger.debug("Starting Honeywell thermostat discovery scan");
        discoverDevices();
    }

    /**
     * Discovers thermostats from the Total Comfort account
     */
    public void discoverDevices() {
        HoneywellTCCBridgeHandler handler = bridgeHandler;
        if (handler == null) {
            logger.debug("Cannot discover devices - no bridge handler available");
            return;
        }

        Collection<Location> locations = handler.getLocations();
        if (locations == null) {
            logger.debug("Cannot discover devices - no locations available");
            return;
        }

        ThingUID bridgeUID = handler.getThing().getUID();

        for (Location location : locations) {
            for (Device device : location.getDevices().values()) {
                String deviceId = device.getDeviceId();
                String locationId = location.getLocationId();

                Map<String, Object> properties = new HashMap<>();
                properties.put(HoneywellTCCBindingConstants.CONFIG_DEVICE_ID, deviceId);
                properties.put(HoneywellTCCBindingConstants.CONFIG_LOCATION_ID, locationId);

                ThingUID thingUID = new ThingUID(HoneywellTCCBindingConstants.THING_TYPE_THERMOSTAT, bridgeUID, deviceId);

                DiscoveryResult result = DiscoveryResultBuilder.create(thingUID)
                        .withBridge(bridgeUID)
                        .withProperties(properties)
                        .withLabel(device.getName() + " (" + location.getName() + ")")
                        .withRepresentationProperty(HoneywellTCCBindingConstants.CONFIG_DEVICE_ID)
                        .build();

                thingDiscovered(result);
                logger.debug("Discovered Honeywell thermostat: {}", result.getLabel());
            }
        }
    }

    @Override
    protected void stopScan() {
        logger.debug("Stopping Honeywell thermostat discovery scan");
        removeOlderResults(getTimestampOfLastScan());
    }

    @Override
    public void deactivate() {
        removeOlderResults(getTimestampOfLastScan());
        super.deactivate();
    }
} 