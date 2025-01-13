package org.openhab.binding.linkplay.internal.upnp;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.device.LinkPlayDeviceManager;
import org.openhab.core.io.transport.upnp.UpnpIOParticipant;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.JsonObject;

@NonNullByDefault
public class LinkPlayUpnpManager implements UpnpIOParticipant {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayUpnpManager.class);

    private final UpnpIOService upnpIOService;
    private final LinkPlayDeviceManager deviceManager;
    private final String deviceId;

    private @Nullable String udn; // Unique Device Name

    public LinkPlayUpnpManager(UpnpIOService upnpIOService, LinkPlayDeviceManager deviceManager, String deviceId) {
        this.upnpIOService = upnpIOService;
        this.deviceManager = deviceManager;
        this.deviceId = deviceId;
    }

    public void register(String udn) {
        this.udn = udn;
        upnpIOService.registerParticipant(this, udn);
        deviceManager.setUpnpSubscriptionState(true);
        logger.debug("Registered UPnP participant for UDN {}", udn);
    }

    public void unregister() {
        if (udn != null) {
            upnpIOService.unregisterParticipant(this);
            deviceManager.setUpnpSubscriptionState(false);
            logger.debug("Unregistered UPnP participant for UDN {}", udn);
        }
    }

    @Override
    public void onValueReceived(String variable, String value, String service) {
        logger.debug("[{}] Received UPnP value: variable={}, value={}, service={}", deviceId, variable, value, service);

        if (variable == null || value == null || service == null) {
            logger.debug("[{}] Skipping incomplete UPnP event", deviceId);
            return;
        }

        try {
            JsonObject upnpStatus = parseUpnpEvent(variable, value, service);
            if (upnpStatus != null) {
                deviceManager.updateChannelsFromUpnp(upnpStatus);
            }
        } catch (Exception e) {
            logger.warn("[{}] Error processing UPnP event: {}", deviceId, e.getMessage(), e);
        }
    }

    @Override
    public void onServiceSubscribed(String service) {
        logger.debug("[{}] Subscribed to UPnP service: {}", deviceId, service);
        deviceManager.setUpnpSubscriptionState(true);
    }

    @Override
    public void onServiceUnsubscribed(String service) {
        logger.warn("[{}] Unsubscribed from UPnP service: {}", deviceId, service);
        deviceManager.setUpnpSubscriptionState(false);
    }

    @Override
    public boolean supportsDevice(String deviceType) {
        return deviceType.equals("urn:schemas-upnp-org:device:MediaRenderer:1");
    }

    @Override
    public @Nullable String getServiceType() {
        return String.join(",",
                "urn:schemas-upnp-org:service:AVTransport:1",
                "urn:schemas-upnp-org:service:RenderingControl:1",
                "urn:schemas-upnp-org:device:MediaRenderer:1");
    }

    private @Nullable JsonObject parseUpnpEvent(String variable, String value, String service) {
        JsonObject.Builder jsonBuilder = Json.createObjectBuilder();

        try {
            switch (getServiceName(service)) {
                case "AVTransport":
                    handleAVTransportEvent(jsonBuilder, variable, value);
                    break;
                case "RenderingControl":
                    handleRenderingControlEvent(jsonBuilder, variable, value);
                    break;
                case "MediaRenderer":
                    handleMediaRendererEvent(jsonBuilder, variable, value);
                    break;
                default:
                    logger.debug("[{}] Unhandled UPnP service: {}", deviceId, service);
                    return null;
            }
        } catch (Exception e) {
            logger.warn("[{}] Error parsing UPnP event: {}", deviceId, e.getMessage(), e);
            return null;
        }

        return jsonBuilder.build();
    }

    private String getServiceName(String service) {
        if (service.contains("AVTransport")) {
            return "AVTransport";
        } else if (service.contains("RenderingControl")) {
            return "RenderingControl";
        } else if (service.contains("MediaRenderer")) {
            return "MediaRenderer";
        }
        return "Unknown";
    }

private void handleAVTransportEvent(JsonObject.Builder jsonBuilder, String variable, String value) {
    switch (variable) {
        case "TransportState":
            jsonBuilder.add("status", "PLAYING".equals(value) ? "play" : "pause");
            break;
        case "CurrentTrackMetaData":
            if (!value.isEmpty()) {
                DIDLParser.MetaData metadata = DIDLParser.parseMetadata(value);
                if (metadata != null) {
                    if (metadata.title != null) {
                        jsonBuilder.add("Title", metadata.title);
                    }
                    if (metadata.artist != null) {
                        jsonBuilder.add("Artist", metadata.artist);
                    }
                    if (metadata.album != null) {
                        jsonBuilder.add("Album", metadata.album);
                    }
                    if (metadata.artworkUrl != null) {
                        jsonBuilder.add("AlbumArt", metadata.artworkUrl);
                    }
                }
            }
            break;
        case "CurrentTrackDuration":
            if (!value.isEmpty() && !"NOT_IMPLEMENTED".equals(value)) {
                String[] parts = value.split(":");
                if (parts.length == 3) {
                    int hours = Integer.parseInt(parts[0]);
                    int minutes = Integer.parseInt(parts[1]);
                    int seconds = Integer.parseInt(parts[2]);
                    int totalSeconds = (hours * 3600) + (minutes * 60) + seconds;
                    jsonBuilder.add("Duration", totalSeconds);
                }
            }
            break;
        default:
            logger.debug("[{}] Unhandled AVTransport variable: {}", deviceId, variable);
    }
}
private void handleRenderingControlEvent(JsonObject.Builder jsonBuilder, String variable, String value) {
    switch (variable) {
        case "Volume":
            jsonBuilder.add("vol", Integer.parseInt(value));
            break;
        case "Mute":
            jsonBuilder.add("mute", "1".equals(value));
            break;
        default:
            logger.debug("[{}] Unhandled RenderingControl variable: {}", deviceId, variable);
    }
}
private void handleMediaRendererEvent(JsonObject.Builder jsonBuilder, String variable, String value) {
    switch (variable) {
        case "PlaybackSpeed":
            jsonBuilder.add("playbackSpeed", value);
            break;
        case "TransportStatus":
            jsonBuilder.add("transportStatus", value);
            break;
        case "PlaybackPosition":
            try {
                String[] parts = value.split(":");
                if (parts.length == 3) {
                    int hours = Integer.parseInt(parts[0]);
                    int minutes = Integer.parseInt(parts[1]);
                    int seconds = Integer.parseInt(parts[2]);
                    int totalSeconds = (hours * 3600) + (minutes * 60) + seconds;
                    jsonBuilder.add("PlaybackPosition", totalSeconds);
                }
            } catch (NumberFormatException e) {
                logger.warn("[{}] Invalid playback position value: {}", deviceId, value);
            }
            break;
        default:
            logger.debug("[{}] Unhandled MediaRenderer variable: {}", deviceId, variable);
    }
}
