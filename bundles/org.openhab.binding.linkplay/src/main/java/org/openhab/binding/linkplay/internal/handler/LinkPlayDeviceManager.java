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
 *
 */
package org.openhab.binding.linkplay.internal.handler;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpManager;
import org.openhab.binding.linkplay.internal.upnp.LinkPlayUpnpManager;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.PlayPauseType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Revised LinkPlayDeviceManager that relies on the HttpManager for
 * all polling and JSON parsing. It updates channels or Thing status
 * based on callbacks from HttpManager and UpnpManager.
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayDeviceManager {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayDeviceManager.class);

    // Channels from your binding constants
    private static final String CHANNEL_CONTROL = "control";
    private static final String CHANNEL_TITLE   = "title";
    private static final String CHANNEL_ARTIST  = "artist";
    private static final String CHANNEL_ALBUM   = "album";
    private static final String CHANNEL_ALBUM_ART = "albumArt";
    private static final String CHANNEL_VOLUME  = "volume";
    private static final String CHANNEL_MUTE    = "mute";
    private static final String CHANNEL_SHUFFLE = "shuffle";
    private static final String CHANNEL_REPEAT  = "repeat";

    private final LinkPlayThingHandler thingHandler;
    private final LinkPlayHttpManager httpManager;
    private final LinkPlayUpnpManager upnpManager;

    private final String deviceId;
    private boolean upnpSubscriptionActive = false;
    private int failedHttpPollCount = 0;
    private static final int MAX_OFFLINE_COUNT = 3; // or from config if desired

    public LinkPlayDeviceManager(LinkPlayThingHandler handler, 
                                 LinkPlayConfiguration config,
                                 LinkPlayHttpManager httpManager,
                                 LinkPlayUpnpManager upnpManager) {
        this.thingHandler = handler;
        this.httpManager  = httpManager;
        this.upnpManager  = upnpManager;
        this.deviceId     = handler.getThing().getUID().getId();

        // Optionally read more from config if needed
        logger.debug("[{}] DeviceManager created with config: {}", deviceId, config);
    }

    /**
     * Called by the ThingHandler to initialize device logic.
     */
    public void initialize() {
        logger.debug("[{}] Initializing DeviceManager...", deviceId);
        // The HTTP manager can be told "go ahead, start polling"
        this.httpManager.startPolling(); 
        // Mark device as ONLINE initially (if config is valid, etc.)
        handleStatusUpdate(ThingStatus.ONLINE);
    }

    /**
     * Called by the HTTP manager whenever a successful poll returns JSON data.
     * If UPnP subscription is active, we might skip or do partial updates.
     */
    public void updateChannelsFromHttp(JsonObject status) {
        if (status == null) {
            logger.debug("[{}] updateChannelsFromHttp called with null JSON; ignoring.", deviceId);
            return;
        }

        // If UPnP is active, you might skip or do partial updates
        if (upnpSubscriptionActive) {
            logger.debug("[{}] UPnP subscription active => skipping some/all HTTP updates", deviceId);
            // Optional: return or do partial updates
        }

        // Example: parse "status"
        if (status.has("status")) {
            String playStatus = getAsString(status, "status");
            updateState(CHANNEL_CONTROL,
                    "play".equalsIgnoreCase(playStatus) ? PlayPauseType.PLAY : PlayPauseType.PAUSE);
        }
        // Title, Artist, Album, etc.
        if (status.has("Title")) {
            updateState(CHANNEL_TITLE, new StringType(getAsString(status, "Title")));
        }
        if (status.has("Artist")) {
            updateState(CHANNEL_ARTIST, new StringType(getAsString(status, "Artist")));
        }
        if (status.has("Album")) {
            updateState(CHANNEL_ALBUM, new StringType(getAsString(status, "Album")));
        }
        if (status.has("AlbumArt")) {
            updateState(CHANNEL_ALBUM_ART, new StringType(getAsString(status, "AlbumArt")));
        }

        // Volume + Mute
        if (status.has("vol")) {
            int vol = getAsInt(status, "vol", 0);
            updateState(CHANNEL_VOLUME, new PercentType(vol));
        }
        if (status.has("mute")) {
            boolean muted = getAsBoolean(status, "mute");
            updateState(CHANNEL_MUTE, OnOffType.from(muted));
        }

        // Shuffle/Repeat if present
        if (status.has("loop")) {
            int loopMode = getAsInt(status, "loop", 0);
            boolean shuffle = (loopMode == 2 || loopMode == 3);
            boolean repeat  = (loopMode == 0 || loopMode == 2);
            updateState(CHANNEL_SHUFFLE, OnOffType.from(shuffle));
            updateState(CHANNEL_REPEAT, OnOffType.from(repeat));
        }

        // Reset any offline count since we got a good response
        failedHttpPollCount = 0;
        if (getThing().getStatus() != ThingStatus.ONLINE) {
            handleStatusUpdate(ThingStatus.ONLINE);
        }
    }

    /**
     * Called by the HTTP manager after a failed poll. Decide if you go OFFLINE or keep retrying.
     */
    public void handleHttpPollFailure(Throwable error) {
        failedHttpPollCount++;
        logger.warn("[{}] HTTP poll failure #{}: {}", deviceId, failedHttpPollCount, error.getMessage());

        if (failedHttpPollCount >= MAX_OFFLINE_COUNT) {
            handleStatusUpdate(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Device unreachable after " + failedHttpPollCount + " attempts");
        }
    }

    /**
     * Manually handle a command from openHAB to the device.
     */
    public void handleCommand(String channelId, Command command) {
        logger.debug("[{}] handleCommand -> channel={}, command={}", deviceId, channelId, command);
        // Possibly delegate to HttpManager or UpnpManager
        this.httpManager.sendChannelCommand(channelId, command);
    }

    public void setUpnpSubscriptionState(boolean active) {
        this.upnpSubscriptionActive = active;
    }

    public boolean isUpnpSubscriptionActive() {
        return upnpSubscriptionActive;
    }

    /**
     * Cleanup.
     */
    public void dispose() {
        logger.debug("[{}] Disposing device manager", deviceId);
        this.httpManager.stopPolling(); // or .dispose() if you want
        // If needed, upnpManager.dispose() is done by the device manager or handler
    }

    // -------------------------------------------------------------------
    // Internal Helpers
    // -------------------------------------------------------------------
    private void updateState(String channelId, State state) {
        thingHandler.handleStateUpdate(channelId, state);
    }

    private String getAsString(JsonObject obj, String key) {
        @Nullable JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive()) {
            return el.getAsString();
        }
        return "";
    }

    private int getAsInt(JsonObject obj, String key, int defaultValue) {
        @Nullable JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive()) {
            try {
                return el.getAsInt();
            } catch (NumberFormatException ignore) {
                // fall through
            }
        }
        return defaultValue;
    }

    private boolean getAsBoolean(JsonObject obj, String key) {
        @Nullable JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return p.getAsBoolean();
            }
            if (p.isString()) {
                String s = p.getAsString();
                return s.equals("true") || s.equals("1");
            }
            if (p.isNumber()) {
                return (p.getAsInt() == 1);
            }
        }
        return false;
    }

    private Thing getThing() {
        return thingHandler.getThing();
    }

    private void handleStatusUpdate(ThingStatus status) {
        thingHandler.handleStatusUpdate(status);
    }

    private void handleStatusUpdate(ThingStatus status, ThingStatusDetail detail, String msg) {
        thingHandler.handleStatusUpdate(status, detail, msg);
    }
}
