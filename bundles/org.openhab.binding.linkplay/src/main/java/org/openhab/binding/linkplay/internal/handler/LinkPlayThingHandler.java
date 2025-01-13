package org.openhab.binding.linkplay.internal.handler;

import org.openhab.binding.linkplay.internal.device.LinkPlayDeviceManager;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpClient;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpManager;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.binding.linkplay.internal.upnp.LinkPlayUpnpManager;
import org.openhab.binding.linkplay.internal.handler.LinkPlayGroupManager;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;

/**
 * Refactored {@link LinkPlayThingHandler} to manage lifecycle and integrate with the Device Manager.
 */
public class LinkPlayThingHandler extends BaseThingHandler {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayThingHandler.class);

    private final LinkPlayDeviceManager deviceManager;

    public LinkPlayThingHandler(Thing thing, LinkPlayHttpClient httpClient, LinkPlayConfiguration config,
                                UpnpIOService upnpIOService) {
        super(thing);

        // Initialize managers
        LinkPlayHttpManager httpManager = new LinkPlayHttpManager(httpClient, config);
        LinkPlayUpnpManager upnpManager = new LinkPlayUpnpManager(upnpIOService);
        LinkPlayGroupManager groupManager = new LinkPlayGroupManager(httpManager);

        // Initialize the device manager
        this.deviceManager = new LinkPlayDeviceManager(config.getIpAddress(), config.getUdn(), httpManager, upnpManager,
                groupManager, Executors.newScheduledThreadPool(1));

        logger.debug("Initialized LinkPlayThingHandler for Thing: {}", thing.getUID());
    }

    @Override
    public void initialize() {
        logger.debug("Initializing LinkPlayThingHandler for Thing: {}", getThing().getUID());

        deviceManager.initialize();
        updateStatus(ThingStatus.ONLINE);
    }

    @Override
    public void dispose() {
        logger.debug("Disposing LinkPlayThingHandler for Thing: {}", getThing().getUID());

        deviceManager.dispose();
        super.dispose();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("Received command: {} for channel: {}", command, channelUID.getIdWithoutGroup());
        deviceManager.handleCommand(channelUID.getIdWithoutGroup(), command.toString());
    }
}
