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
package org.openhab.binding.linkplay.internal.handler;

import static org.openhab.binding.linkplay.internal.LinkPlayBindingConstants.*;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpClient;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LinkPlayMetadataManager} handles metadata updates for a LinkPlay device.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayMetadataManager {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayMetadataManager.class);
    private final LinkPlayHttpClient httpClient;

    public LinkPlayMetadataManager(LinkPlayHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Fetches metadata from the device and updates the Thing properties and states.
     *
     * @param thing The Thing instance to update.
     */
    public boolean fetchAndUpdateMetadata(Thing thing) {
        try {
            CompletableFuture<String> metadataFuture = httpClient.sendCommand("getStatusEx");

            metadataFuture.whenComplete((response, error) -> {
                if (error != null) {
                    logger.warn("Failed to fetch metadata: {}", error.getMessage());
                    return;
                }
                parseAndApplyMetadata(response, thing);
            });

            return true;
        } catch (Exception e) {
            logger.warn("Failed to fetch metadata: {}", e.getMessage());
            return false;
        }
    }

    private void parseAndApplyMetadata(String response, Thing thing) {
        try {
            String deviceName = extractJsonValue(response, "DeviceName");
            String firmwareVersion = extractJsonValue(response, "firmware");
            String macAddress = extractJsonValue(response, "MAC");
            String ipAddress = extractJsonValue(response, "eth2");

            // Update Thing properties
            thing.setProperty("deviceName", deviceName);
            thing.setProperty("firmwareVersion", firmwareVersion);
            thing.setProperty("macAddress", macAddress);
            thing.setProperty("ipAddress", ipAddress);

            // Update channel states
            updateState(thing, CHANNEL_IP_ADDRESS, new StringType(ipAddress));
            updateState(thing, CHANNEL_MAC_ADDRESS, new StringType(macAddress));

            logger.debug("Metadata updated: deviceName={}, firmwareVersion={}, macAddress={}, ipAddress={}", deviceName,
                    firmwareVersion, macAddress, ipAddress);
        } catch (Exception e) {
            logger.warn("Error parsing metadata response: {}", e.getMessage(), e);
        }
    }

    private void updateState(Thing thing, String channelId, State state) {
        Optional<Channel> channelOpt = thing.getChannels().stream()
                .filter(channel -> channelId.equals(channel.getUID().getId())).findFirst();

        if (channelOpt.isPresent() && thing.getHandler() instanceof LinkPlayThingHandler handler) {
            ChannelUID channelUID = new ChannelUID(thing.getUID(), channelId);
            handler.updateThingState(channelUID, state);
        }
    }

    private String extractJsonValue(String json, String key) {
        try {
            int startIndex = json.indexOf("\"" + key + "\":");
            if (startIndex == -1) {
                return "";
            }
            startIndex = json.indexOf(':', startIndex) + 1;
            int endIndex = json.indexOf(',', startIndex);
            if (endIndex == -1) {
                endIndex = json.indexOf('}', startIndex);
            }

            String value = json.substring(startIndex, endIndex).trim();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            return value;
        } catch (Exception e) {
            logger.warn("Error extracting JSON value for key {}: {}", key, e.getMessage());
            return "";
        }
    }
}
