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

import java.util.concurrent.CompletableFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpClient;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Thing;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LinkPlayMetadataManager} handles metadata updates for a LinkPlay device.
 *
 * It fetches metadata such as device name, firmware version, and network properties,
 * and updates the corresponding Thing properties and states.
 *
 * @author Michael Cumming
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
    public void fetchAndUpdateMetadata(Thing thing) {
        CompletableFuture<String> metadataFuture = httpClient.sendCommand("getStatusEx");

        metadataFuture.whenComplete((response, error) -> {
            if (error != null) {
                logger.warn("Failed to fetch metadata: {}", error.getMessage());
                return;
            }

            if (response != null) {
                parseAndApplyMetadata(response, thing);
            } else {
                logger.warn("Empty metadata response received from device.");
            }
        });
    }

    /**
     * Parses the metadata response and updates the Thing properties and states.
     *
     * @param response The raw metadata response string from the device.
     * @param thing The Thing instance to update.
     */
    private void parseAndApplyMetadata(String response, Thing thing) {
        try {
            // Example parsing logic - assumes a simple JSON structure for demonstration purposes.
            String deviceName = extractJsonValue(response, "DeviceName");
            String firmwareVersion = extractJsonValue(response, "firmware");
            String macAddress = extractJsonValue(response, "MAC");
            String ipAddress = extractJsonValue(response, "eth2");

            // Update Thing properties
            thing.setProperty("deviceName", deviceName);
            thing.setProperty("firmwareVersion", firmwareVersion);
            thing.setProperty("macAddress", macAddress);
            thing.setProperty("ipAddress", ipAddress);

            // Example state updates (if applicable)
            updateState(thing, "deviceName", new StringType(deviceName));
            updateState(thing, "firmwareVersion", new StringType(firmwareVersion));

            logger.debug("Metadata updated: deviceName={}, firmwareVersion={}, macAddress={}, ipAddress={}", deviceName,
                    firmwareVersion, macAddress, ipAddress);

        } catch (Exception e) {
            logger.warn("Error parsing metadata response: {}", e.getMessage(), e);
        }
    }

    /**
     * Extracts a JSON value for a given key from the raw response string.
     *
     * @param json The raw JSON response string.
     * @param key The key to extract the value for.
     * @return The extracted value or an empty string if not found.
     */
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

    /**
     * Updates a channel state for a Thing.
     *
     * @param thing The Thing instance to update.
     * @param channelId The channel ID to update.
     * @param state The new state to set.
     */
    private void updateState(Thing thing, String channelId, State state) {
        thing.getChannel(channelId).ifPresent(channel -> {
            if (thing.getHandler() instanceof LinkPlayHandler handler) {
                handler.updateState(channel.getUID(), state);
            }
        });
    }
}
