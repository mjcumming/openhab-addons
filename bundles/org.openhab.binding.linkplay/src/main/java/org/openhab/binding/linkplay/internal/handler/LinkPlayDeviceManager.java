package org.openhab.binding.linkplay.internal.device;

import javax.json.JsonObject;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpManager;
import org.openhab.binding.linkplay.internal.upnp.LinkPlayUpnpManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages device-specific details and communication for a LinkPlay device.
 * Encapsulates the HTTP and UPnP managers for modularity.
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
     * Fetches the latest device status via HTTP and updates device channels.
     * 
     * @return A JsonObject containing the device status.
     */
    public JsonObject fetchDeviceStatus() {
        try {
            JsonObject status = httpManager.getPlayerStatus().get(); // Blocking call for simplicity
            logger.debug("Fetched device status: {}", status);
            return status;
        } catch (Exception e) {
            logger.warn("Failed to fetch device status for {}: {}", deviceId, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Registers the device with UPnP services.
     */
    public void registerUpnp() {
        upnpManager.register(deviceId);
        logger.debug("Registered UPnP for device: {}", deviceId);
    }

    /**
     * Unregisters the device from UPnP services.
     */
    public void unregisterUpnp() {
        upnpManager.unregister();
        logger.debug("Unregistered UPnP for device: {}", deviceId);
    }
}
