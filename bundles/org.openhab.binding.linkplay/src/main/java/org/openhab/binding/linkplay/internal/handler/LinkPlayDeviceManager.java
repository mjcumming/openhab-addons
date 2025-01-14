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

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpManager;
import org.openhab.binding.linkplay.internal.upnp.LinkPlayUpnpManager;
import org.openhab.core.config.core.Configuration;
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

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.StringReader;


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

    // The validated config object from the handler/factory
    private final LinkPlayConfiguration config;

    private final String deviceId;
    private boolean upnpSubscriptionActive = false;
    private int failedHttpPollCount = 0;
    private static final int MAX_OFFLINE_COUNT = 3; // optionally read from config if desired

    public LinkPlayDeviceManager(
            LinkPlayThingHandler thingHandler,
            LinkPlayConfiguration config,
            LinkPlayHttpManager httpManager,
            LinkPlayUpnpManager upnpManager) {

        this.thingHandler = thingHandler;
        this.config = config;
        this.httpManager  = httpManager;
        this.upnpManager  = upnpManager;
        this.deviceId     = thingHandler.getThing().getUID().getId();

        logger.debug("[{}] DeviceManager created with config: {}", deviceId, config);
    }

    /**
     * Called by the ThingHandler to initialize device logic.
     */
    public void initialize() {
        logger.debug("[{}] Initializing DeviceManager...", deviceId);

        // 1) Start HTTP polling
        httpManager.startPolling();

        // 2) If we already have a UDN from config, register UPnP now
        String existingUdn = config.getUdn();
        if (existingUdn != null && !existingUdn.isEmpty()) {
            logger.debug("[{}] We already have UDN '{}', registering UPnP now", deviceId, existingUdn);
            upnpManager.register(existingUdn);
        } else {
            logger.debug("[{}] No UDN yet, will rely on HTTP first and register UPnP later", deviceId);
        }

        // Mark device as ONLINE if HTTP is good
        handleStatusUpdate(ThingStatus.ONLINE);
    }

    /**
     * Called by the HTTP manager whenever a successful poll returns JSON data.
     */
    public void updateChannelsFromHttp(JsonObject status) {
        if (status == null) {
            logger.debug("[{}] updateChannelsFromHttp called with null JSON; ignoring.", deviceId);
            return;
        }

        // Possibly discover the UDN from the device's extended status?
        String discoveredUdn = parseUdnFromHttp(status);
        if (discoveredUdn != null && !discoveredUdn.isEmpty()) {
            if (config.getUdn().isEmpty() || !config.getUdn().equals(discoveredUdn)) {
                logger.debug("[{}] Discovered new UDN via HTTP: {}", deviceId, discoveredUdn);
                storeUdnInConfig(discoveredUdn);
                upnpManager.register(discoveredUdn);
            }
        }

        // If UPnP is active, decide if you want to skip or partially skip
        if (upnpSubscriptionActive) {
            logger.debug("[{}] UPnP subscription active => skipping some/all HTTP updates", deviceId);
            // Optionally, you can skip or do partial updates
            // For demonstration, let's do full updates anyway.
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

        // Reset any offline count since poll succeeded
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

    /**
     * Called by the UpnpManager to indicate subscription success/failure.
     */
    public void setUpnpSubscriptionState(boolean active) {
        logger.debug("[{}] setUpnpSubscriptionState => {}", deviceId, active);
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

        // Stop HTTP polling
        httpManager.stopPolling();

        // Dispose UPnP manager
        upnpManager.dispose();
    }

    // -------------------------------------------------------------------
    // Internal Helpers
    // -------------------------------------------------------------------
    private void updateState(String channelId, State state) {
        thingHandler.handleStateUpdate(channelId, state);
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

    /**
     * If we discover a new UDN, store it in config so user sees it
     * and so we have it for next time the binding restarts.
     */
    private void storeUdnInConfig(String newUdn) {
        if (newUdn.isEmpty() || newUdn.equals(config.getUdn())) {
            return; // No change
        }

        logger.debug("[{}] Storing discovered UDN '{}' into config", deviceId, newUdn);

        // 1) Update our in-memory config
        // (We might add a setUdn(...) method to LinkPlayConfiguration or do reflection here.)
        // For demonstration, let's do it via reflection or direct field if you add a setter:
        // config.setUdn(newUdn); // if you add a setUdn in LinkPlayConfiguration
        // For now, let's assume we can do it manually:
        // (In reality, you'd likely add a 'setUdn()' method to LinkPlayConfiguration.)

        // 2) Update the actual Thing config so it’s persisted
        Map<String, Object> cfgMap = new HashMap<>(getThing().getConfiguration().getProperties());
        cfgMap.put("udn", newUdn);
        thingHandler.updateConfiguration(new Configuration(cfgMap));

        // This ensures on next restart, fromConfiguration() sees the new UDN.
    }

    /**
     * Example logic to parse UDN from an HTTP response.
     * Adjust as needed based on actual device JSON.
     */
    private @Nullable String parseUdnFromHttp(JsonObject json) {
        // Some devices might return something like: { "udn":"uuid:LinkPlay_ABC123" }
        if (json.has("udn")) {
            String found = getAsString(json, "udn");
            if (!found.isEmpty() && !found.startsWith("uuid:")) {
                found = "uuid:" + found;
            }
            return found;
        }
        return null;
    }

private String getAsString(JsonObject obj, String key) {
    return obj.containsKey(key) ? obj.getString(key, "") : "";
}

private int getAsInt(JsonObject obj, String key, int defaultValue) {
    if (obj.containsKey(key)) {
        try {
            return obj.getInt(key, defaultValue);
        } catch (Exception e) {
            logger.debug("Invalid integer value for key '{}': {}", key, e.getMessage());
        }
    }
    return defaultValue;
}

private boolean getAsBoolean(JsonObject obj, String key) {
    if (obj.containsKey(key)) {
        try {
            return obj.getBoolean(key);
        } catch (Exception e) {
            // Fallback for numeric or string representations
            String value = obj.getString(key, "false");
            return value.equalsIgnoreCase("true") || value.equals("1");
        }
    }
    return false;
}

}
