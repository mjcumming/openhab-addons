package org.openhab.binding.linkplay.internal.upnp;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.device.LinkPlayDeviceManager;
import org.openhab.core.io.transport.upnp.UpnpIOParticipant;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.JsonObject;

/**
 * Manages UPnP interactions for LinkPlay devices, including subscriptions,
 * event handling, and notifying the LinkPlayDeviceManager of updates.
 * 
 * @author Michael Cumming
 */
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

    /**
     * Registers the UPnP participant and initializes subscriptions.
     * 
     * @param udn Unique Device Name of the device.
     */
    public void register(String udn) {
        this.udn = udn;
        upnpIOService.registerParticipant(this, udn);
        deviceManager.setUpnpSubscriptionState(true); // Notify that subscription is active
        logger.debug("Registered UPnP participant for UDN {}", udn);
    }

    /**
     * Unregisters the UPnP participant and notifies the device manager.
     */
    public void unregister() {
        if (udn != null) {
            upnpIOService.unregisterParticipant(this);
            deviceManager.setUpnpSubscriptionState(false); // Notify that subscription is inactive
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
                deviceManager.updateChannelsFromUpnp(upnpStatus); // Delegate to Device Manager
            }
        } catch (Exception e) {
            logger.warn("[{}] Error processing UPnP event: {}", deviceId, e.getMessage(), e);
        }
    }

    @Override
    public void onServiceSubscribed(String service) {
        logger.debug("[{}] Subscribed to UPnP service: {}", deviceId, service);
        deviceManager.setUpnpSubscriptionState(true); // Mark subscription as active
    }

    @Override
    public void onServiceUnsubscribed(String service) {
        logger.warn("[{}] Unsubscribed from UPnP service: {}", deviceId, service);
        deviceManager.setUpnpSubscriptionState(false); // Mark subscription as inactive
    }

    @Override
    public boolean supportsDevice(String deviceType) {
        return deviceType.equals("urn:schemas-upnp-org:device:MediaRenderer:1");
    }

    @Override
    public @Nullable String getServiceType() {
        return null; // Provide specific service type if applicable
    }

    /**
     * Parses a UPnP event into a JsonObject for further processing.
     * 
     * @param variable The event variable.
     * @param value The event value.
     * @param service The event service.
     * @return A JsonObject representing the parsed event, or null if invalid.
     */
    private @Nullable JsonObject parseUpnpEvent(String variable, String value, String service) {
        // Implement parsing logic here to convert UPnP event data into a JsonObject.
        // Example:
        // {
        //     "status": "play",
        //     "volume": 50,
        //     "mute": false
        // }
        return null; // Replace with actual parsing logic
    }
}
