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
package org.openhab.binding.linkplay.internal.transport.upnp;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.LinkPlayDeviceManager;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LinkPlayUpnpManager} handles UPnP communication with a LinkPlay device.
 * It uses SOAP calls to manage playback queues, metadata, and playlists.
 * <p>
 * Any changes to the queue trigger a callback to the device manager.
 * 
 * @author Michael Cumming - Initial Contribution
 */
@NonNullByDefault
public class LinkPlayUpnpManager {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayUpnpManager.class);

    private final UpnpIOService upnpIOService;
    private final LinkPlayDeviceManager deviceManager;

    private static final String SERVICE_PLAYQUEUE = "urn:schemas-wiimu-com:service:PlayQueue:1";
    private static final String SERVICE_AVTRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";

    private @Nullable String udn;

    /**
     * Constructor.
     * 
     * @param upnpIOService The openHAB UPnP IO service
     * @param deviceManager The device manager handling device logic
     */
    public LinkPlayUpnpManager(UpnpIOService upnpIOService, LinkPlayDeviceManager deviceManager) {
        this.upnpIOService = upnpIOService;
        this.deviceManager = deviceManager;

        // Register UDN from device config, if available
        String configUdn = deviceManager.getConfig().getUdn();
        if (!configUdn.isEmpty()) {
            this.udn = configUdn.startsWith("uuid:") ? configUdn : "uuid:" + configUdn;
            logger.debug("[{}] Initialized LinkPlayUpnpManager with UDN={}", deviceManager.getConfig().getDeviceName(),
                    this.udn);
        }
    }

    /**
     * Fetch the current playback queue.
     */
    public void fetchQueue() {
        if (udn == null) {
            logger.warn("Cannot fetch queue - UDN is not set.");
            return;
        }

        logger.debug("[{}] Fetching playback queue via SOAP BrowseQueue...",
                deviceManager.getConfig().getDeviceName());

        try {
            Map<String, String> arguments = new HashMap<>();
            arguments.put("QueueName", "TotalQueue");

            String response = upnpIOService.execute(udn, SERVICE_PLAYQUEUE, "BrowseQueue", arguments);
            logger.debug("[{}] Received SOAP response for BrowseQueue: {}", deviceManager.getConfig().getDeviceName(),
                    response);

            Map<String, String> queueDetails = DIDLParser.parsePlaylist(response);
            deviceManager.updatePlayerQueue(queueDetails);

        } catch (Exception e) {
            logger.error("[{}] Failed to fetch playback queue: {}", deviceManager.getConfig().getDeviceName(),
                    e.getMessage(), e);
        }
    }

    /**
     * Play a specific track in the queue by its index.
     * 
     * @param index The index of the track to play (0-based).
     */
    public void playTrackAtIndex(int index) {
        if (udn == null) {
            logger.warn("Cannot play track - UDN is not set.");
            return;
        }

        logger.debug("[{}] Playing track at index {} via SOAP PlayQueueWithIndex...",
                deviceManager.getConfig().getDeviceName(), index);

        try {
            Map<String, String> arguments = new HashMap<>();
            arguments.put("QueueName", "TotalQueue");
            arguments.put("Index", String.valueOf(index));

            upnpIOService.execute(udn, SERVICE_PLAYQUEUE, "PlayQueueWithIndex", arguments);

            logger.info("[{}] Track at index {} is now playing.", deviceManager.getConfig().getDeviceName(), index);

        } catch (Exception e) {
            logger.error("[{}] Failed to play track at index {}: {}", deviceManager.getConfig().getDeviceName(), index,
                    e.getMessage(), e);
        }
    }

    /**
     * Add a track to the queue.
     * 
     * @param metadata Metadata or URI of the track to append.
     */
    public void addTrackToQueue(String metadata) {
        if (udn == null) {
            logger.warn("Cannot add track - UDN is not set.");
            return;
        }

        logger.debug("[{}] Adding track to queue via SOAP AppendQueue...",
                deviceManager.getConfig().getDeviceName());

        try {
            Map<String, String> arguments = new HashMap<>();
            arguments.put("QueueContext", metadata);

            upnpIOService.execute(udn, SERVICE_PLAYQUEUE, "AppendQueue", arguments);

            logger.info("[{}] Track added to queue.", deviceManager.getConfig().getDeviceName());

            // Trigger queue update
            fetchQueue();

        } catch (Exception e) {
            logger.error("[{}] Failed to add track to queue: {}", deviceManager.getConfig().getDeviceName(),
                    e.getMessage(), e);
        }
    }

    /**
     * Remove tracks from the queue by range.
     * 
     * @param startIndex The start index of the range.
     * @param endIndex   The end index of the range.
     */
    public void removeTracksFromQueue(int startIndex, int endIndex) {
        if (udn == null) {
            logger.warn("Cannot remove tracks - UDN is not set.");
            return;
        }

        logger.debug("[{}] Removing tracks from index {} to {} via SOAP RemoveTracksInQueue...",
                deviceManager.getConfig().getDeviceName(), startIndex, endIndex);

        try {
            Map<String, String> arguments = new HashMap<>();
            arguments.put("QueueName", "TotalQueue");
            arguments.put("RangStart", String.valueOf(startIndex));
            arguments.put("RangEnd", String.valueOf(endIndex));

            upnpIOService.execute(udn, SERVICE_PLAYQUEUE, "RemoveTracksInQueue", arguments);

            logger.info("[{}] Tracks from index {} to {} removed from queue.",
                    deviceManager.getConfig().getDeviceName(), startIndex, endIndex);

            // Trigger queue update
            fetchQueue();

        } catch (Exception e) {
            logger.error("[{}] Failed to remove tracks from queue: {}", deviceManager.getConfig().getDeviceName(),
                    e.getMessage(), e);
        }
    }

    /**
     * Set the playback loop mode.
     * 
     * @param mode The loop mode (0: no loop, 1: loop one, 2: loop all).
     */
    public void setLoopMode(int mode) {
        if (udn == null) {
            logger.warn("Cannot set loop mode - UDN is not set.");
            return;
        }

        logger.debug("[{}] Setting loop mode to {} via SOAP SetQueueLoopMode...",
                deviceManager.getConfig().getDeviceName(), mode);

        try {
            Map<String, String> arguments = new HashMap<>();
            arguments.put("LoopMode", String.valueOf(mode));

            upnpIOService.execute(udn, SERVICE_PLAYQUEUE, "SetQueueLoopMode", arguments);

            logger.info("[{}] Loop mode set to {}.", deviceManager.getConfig().getDeviceName(), mode);

        } catch (Exception e) {
            logger.error("[{}] Failed to set loop mode: {}", deviceManager.getConfig().getDeviceName(), e.getMessage(),
                    e);
        }
    }

    /**
     * Search for tracks in the queue by a search key.
     * 
     * @param searchKey The search term.
     */
    public void searchQueue(String searchKey) {
        if (udn == null) {
            logger.warn("Cannot search queue - UDN is not set.");
            return;
        }

        logger.debug("[{}] Searching queue for '{}' via SOAP SearchQueueOnline...",
                deviceManager.getConfig().getDeviceName(), searchKey);

        try {
            Map<String, String> arguments = new HashMap<>();
            arguments.put("QueueName", "TotalQueue");
            arguments.put("SearchKey", searchKey);
            arguments.put("Queuelimit", "10"); // Optional limit

            String response = upnpIOService.execute(udn, SERVICE_PLAYQUEUE, "SearchQueueOnline", arguments);
            logger.debug("[{}] Received SOAP response for SearchQueueOnline: {}", deviceManager.getConfig().getDeviceName(),
                    response);

            Map<String, String> searchResults = DIDLParser.parsePlaylist(response);
            deviceManager.updatePlayerQueue(searchResults);

        } catch (Exception e) {
            logger.error("[{}] Failed to search queue: {}", deviceManager.getConfig().getDeviceName(), e.getMessage(),
                    e);
        }
    }
}
