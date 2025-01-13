package org.openhab.binding.linkplay.internal.device;

import javax.json.JsonObject;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpManager;
import org.openhab.binding.linkplay.internal.upnp.LinkPlayUpnpManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages device-specific details and communication for a LinkPlay device.
 * Encapsulates the HTTP and UPnP managers for modularity.
 * Prioritizes UPnP updates, falling back to HTTP if necessary.
 * 
 * @author Michael Cumming
 */
@NonNullByDefault
public class LinkPlayDeviceManager {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayDeviceManager.class);

    private final String ipAddress;
    private final String deviceId;

    private final LinkPlayHttpManager httpManager;
    private final LinkPlayUpnpManager upnpManager;

    private boolean upnpSubscriptionActive = true; // Tracks whether UPnP events are available

    public LinkPlayDeviceManager(String ipAddress, String deviceId, LinkPlayHttpManager httpManager,
            LinkPlayUpnpManager upnpManager) {
        this.ipAddress = ipAddress;
        this.deviceId = deviceId;
        this.httpManager = httpManager;
        this.upnpManager = upnpManager;

        httpManager.setIpAddress(ipAddress);
        logger.debug("Initialized LinkPlayDeviceManager for device: {}", deviceId);
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public LinkPlayHttpManager getHttpManager() {
        return httpManager;
    }

    public LinkPlayUpnpManager getUpnpManager() {
        return upnpManager;
    }

    /**
     * Updates channels from UPnP data if the subscription is active.
     * 
     * @param status The UPnP status response as a JsonObject.
     */
    public void updateChannelsFromUpnp(JsonObject status) {
        if (!upnpSubscriptionActive) {
            logger.debug("[{}] Skipping UPnP update because subscription is inactive", deviceId);
            return;
        }

        logger.debug("[{}] Processing UPnP update", deviceId);
        updatePlaybackChannels(status, "UPnP");
        updateDeviceChannels(status, "UPnP");
    }

    /**
     * Updates channels from HTTP data if UPnP subscription is inactive.
     * 
     * @param status The HTTP status response as a JsonObject.
     */
    public void updateChannelsFromHttp(JsonObject status) {
        if (upnpSubscriptionActive) {
            logger.debug("[{}] Ignoring HTTP update because UPnP subscription is active", deviceId);
            return;
        }

        logger.debug("[{}] Processing HTTP update as fallback", deviceId);
        updatePlaybackChannels(status, "HTTP");
        updateDeviceChannels(status, "HTTP");
    }

    /**
     * Updates the UPnP subscription state.
     * 
     * @param active True if UPnP subscription is active, false otherwise.
     */
    public void setUpnpSubscriptionState(boolean active) {
        upnpSubscriptionActive = active;
        logger.debug("[{}] Set UPnP subscription state to {}", deviceId, active ? "active" : "inactive");
    }

    /**
     * Updates playback-related channels.
     * 
     * @param status The status response as a JsonObject.
     * @param source The source of the update (e.g., "UPnP", "HTTP").
     */
private void updatePlaybackChannels(JsonObject status, String source) {
    logger.debug("[{}] Updating playback channels (source: {})", deviceId, source);

    // Playback Status
    if (status.has("status")) {
        String playStatus = status.get("status").getAsString();
        updateChannelState(CHANNEL_CONTROL, "play".equals(playStatus) ? PlayPauseType.PLAY : PlayPauseType.PAUSE);
    }

    // Position and Duration
    if (status.has("curpos") && status.has("totlen")) {
        try {
            int position = status.get("curpos").getAsInt() / 1000; // Convert to seconds
            int duration = status.get("totlen").getAsInt() / 1000;
            updateChannelState(CHANNEL_POSITION, new QuantityType<>(position, Units.SECOND));
            updateChannelState(CHANNEL_DURATION, new QuantityType<>(duration, Units.SECOND));
        } catch (NumberFormatException e) {
            logger.debug("[{}] Invalid position/duration values in status (source: {})", deviceId, source);
        }
    }

    // Metadata: Title, Artist, Album
    updateHexEncodedMetadata(status, "Title", CHANNEL_TITLE);
    updateHexEncodedMetadata(status, "Artist", CHANNEL_ARTIST);
    updateHexEncodedMetadata(status, "Album", CHANNEL_ALBUM);

    // Album Art
    if (status.has("AlbumArt")) {
        String albumArt = status.get("AlbumArt").getAsString();
        if (!albumArt.isEmpty()) {
            updateChannelState(CHANNEL_ALBUM_ART, new StringType(albumArt));
        }
    }

    // Volume
    if (status.has("vol")) {
        try {
            int volume = status.get("vol").getAsInt();
            if (volume >= 0 && volume <= 100) {
                updateChannelState(CHANNEL_VOLUME, new PercentType(volume));
            }
        } catch (NumberFormatException e) {
            logger.warn("[{}] Invalid volume value in status (source: {})", deviceId, source);
        }
    }

    // Mute
    if (status.has("mute")) {
        try {
            boolean muted = status.get("mute").getAsInt() == 1;
            updateChannelState(CHANNEL_MUTE, OnOffType.from(muted));
        } catch (NumberFormatException e) {
            logger.warn("[{}] Invalid mute value in status (source: {})", deviceId, source);
        }
    }

    // Playback Modes (Shuffle and Repeat)
    if (status.has("loop")) {
        try {
            int loopMode = status.get("loop").getAsInt();
            boolean shuffle = (loopMode == 2 || loopMode == 3);
            boolean repeat = (loopMode == 0 || loopMode == 2);
            updateChannelState(CHANNEL_SHUFFLE, OnOffType.from(shuffle));
            updateChannelState(CHANNEL_REPEAT, OnOffType.from(repeat));
        } catch (NumberFormatException e) {
            logger.debug("[{}] Invalid loop mode value in status (source: {})", deviceId, source);
        }
    }

    // Source
    if (status.has("mode")) {
        String mode = status.get("mode").getAsString();
        updateChannelState(CHANNEL_SOURCE, new StringType(mapModeToSource(mode)));
    }
}

        /**
     * Updates device-related channels.
     * 
     * @param status The status response as a JsonObject.
     * @param source The source of the update (e.g., "UPnP", "HTTP").
     */
private void updateDeviceChannels(JsonObject status, String source) {
    logger.debug("[{}] Updating device channels (source: {})", deviceId, source);

    // Device Name
    if (status.has("DeviceName")) {
        updateChannelState(CHANNEL_DEVICE_NAME, new StringType(status.get("DeviceName").getAsString()));
    }

    // Firmware Version
    if (status.has("firmware")) {
        updateChannelState(CHANNEL_FIRMWARE, new StringType(status.get("firmware").getAsString()));
    }

    // MAC Address
    if (status.has("mac")) {
        updateChannelState(CHANNEL_MAC_ADDRESS, new StringType(status.get("mac").getAsString()));
    }

    // IP Address
    if (status.has("ip")) {
        updateChannelState(CHANNEL_IP_ADDRESS, new StringType(status.get("ip").getAsString()));
    }

    // WiFi Signal Strength
    if (status.has("wifi_signal")) {
        try {
            int signalStrength = status.get("wifi_signal").getAsInt();
            if (signalStrength >= 0 && signalStrength <= 100) {
                updateChannelState(CHANNEL_WIFI_SIGNAL, new PercentType(signalStrength));
            }
        } catch (NumberFormatException e) {
            logger.warn("[{}] Invalid WiFi signal value in status (source: {})", deviceId, source);
        }
    }
}
