package org.openhab.binding.linkplay.internal.handler;

import static org.openhab.binding.linkplay.internal.LinkPlayBindingConstants.*;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.device.LinkPlayDeviceManager;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpClient;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpManager;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.binding.linkplay.internal.upnp.LinkPlayUpnpManager;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refactored {@link LinkPlayThingHandler} to manage lifecycle and integrate DeviceManager, UPnP, and HTTP managers.
 */
@NonNullByDefault
public class LinkPlayThingHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayThingHandler.class);

    private final LinkPlayDeviceManager deviceManager;
    private final LinkPlayHttpManager httpManager;
    private final LinkPlayUpnpManager upnpManager;
    private final LinkPlayGroupManager groupManager;

    private @Nullable ScheduledFuture<?> pollingJob;

    public LinkPlayThingHandler(Thing thing, LinkPlayHttpClient httpClient, LinkPlayConfiguration config, UpnpIOService upnpIOService) {
        super(thing);
        this.httpManager = new LinkPlayHttpManager(httpClient, config);
        this.deviceManager = new LinkPlayDeviceManager(config.getIpAddress(), config.getUdn(), httpManager, upnpIOService);
        this.upnpManager = new LinkPlayUpnpManager(upnpIOService, deviceManager, thing.getUID().getId());
        this.groupManager = new LinkPlayGroupManager(httpManager);
    }

    @Override
    public void initialize() {
        String ipAddress = (String) getThing().getConfiguration().get(CONFIG_IP_ADDRESS);

        if (ipAddress == null || ipAddress.isEmpty()) {
            logger.warn("IP address not configured for Thing: {}", getThing().getUID());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "IP address not configured");
            return;
        }

        httpManager.setIpAddress(ipAddress);
        String udn = (String) getThing().getConfiguration().get(CONFIG_DEVICE_ID);
        if (udn != null && !udn.isEmpty()) {
            upnpManager.register(udn);
        } else {
            logger.warn("UPnP UDN not configured for Thing: {}", getThing().getUID());
        }

        updateStatus(ThingStatus.ONLINE);

    }

@Override
public void dispose() {
    logger.debug("Disposing LinkPlayThingHandler for Thing: {}", getThing().getUID());

    // Stop polling
    stopPolling();

    // Unregister UPnP Manager
    if (upnpManager != null) {
        upnpManager.unregister();
    }

    // Dispose Group Manager
    if (groupManager != null) {
        groupManager.dispose();
    }

    // Dispose HTTP Manager
    if (httpManager != null) {
        httpManager.dispose();
    }

    // Dispose Device Manager
    if (deviceManager != null) {
        deviceManager.dispose();
    }

    super.dispose();
}




    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        deviceManager.handleCommand(channelUID, command);
    }
    }


}
