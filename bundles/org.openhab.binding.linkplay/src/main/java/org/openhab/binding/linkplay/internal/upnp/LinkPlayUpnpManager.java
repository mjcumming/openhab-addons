package org.openhab.binding.linkplay.internal.upnp;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.io.transport.upnp.UpnpIOParticipant;
import org.openhab.core.thing.Thing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages UPnP interactions for LinkPlay devices, including subscriptions,
 * event handling, and periodic subscription renewals.
 */
@NonNullByDefault
public class LinkPlayUpnpManager implements UpnpIOParticipant {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayUpnpManager.class);

    private static final int SUBSCRIPTION_RENEWAL_INTERVAL_SECONDS = 300; // 5 minutes

    private final UpnpIOService upnpIOService;
    private final Thing thing;
    private final ScheduledExecutorService scheduler;

    private @Nullable String udn; // Unique Device Name
    private @Nullable ScheduledExecutorService subscriptionRenewalTask;

    public LinkPlayUpnpManager(UpnpIOService upnpIOService, Thing thing, ScheduledExecutorService scheduler) {
        this.upnpIOService = upnpIOService;
        this.thing = thing;
        this.scheduler = scheduler;
    }

    /**
     * Registers the UPnP participant and initializes subscriptions.
     *
     * @param udn Unique Device Name of the device.
     */
    public void register(String udn) {
        this.udn = udn;
        upnpIOService.registerParticipant(this, udn);
        scheduleSubscriptionRenewal();
        logger.debug("Registered UPnP participant for UDN {}", udn);
    }

    /**
     * Unregisters the UPnP participant and cleans up subscriptions.
     */
    public void unregister() {
        if (udn != null) {
            upnpIOService.unregisterParticipant(this);
            cancelSubscriptionRenewal();
            logger.debug("Unregistered UPnP participant for UDN {}", udn);
        }
    }

    /**
     * Schedules periodic subscription renewals.
     */
    private void scheduleSubscriptionRenewal() {
        cancelSubscriptionRenewal(); // Ensure no duplicate tasks

        subscriptionRenewalTask = scheduler.scheduleAtFixedRate(() -> {
            if (udn != null) {
                logger.debug("Renewing UPnP subscriptions for UDN {}", udn);
                upnpIOService.renewSubscriptions(this, udn);
            }
        }, SUBSCRIPTION_RENEWAL_INTERVAL_SECONDS, SUBSCRIPTION_RENEWAL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Cancels any scheduled subscription renewal tasks.
     */
    private void cancelSubscriptionRenewal() {
        if (subscriptionRenewalTask != null) {
            subscriptionRenewalTask.shutdownNow();
            subscriptionRenewalTask = null;
        }
    }

    @Override
    public void onValueReceived(String variable, String value, String service) {
        logger.debug("UPnP event received: variable={}, value={}, service={}", variable, value, service);

        // Update Thing channels based on received UPnP events
        // TODO: Implement mapping from UPnP variables to Thing channels.
    }

    @Override
    public void onServiceSubscribed(String service) {
        logger.debug("Subscribed to UPnP service: {}", service);
    }

    @Override
    public void onServiceUnsubscribed(String service) {
        logger.debug("Unsubscribed from UPnP service: {}", service);
    }

    @Override
    public @Nullable String getServiceType() {
        // Return the relevant service type for this UPnP participant
        return null; // This should be implemented to return specific service types if needed.
    }

    @Override
    public boolean supportsDevice(String deviceType) {
        // Validate if the device type matches LinkPlay-specific UPnP devices
        return deviceType.equals("urn:schemas-upnp-org:device:MediaRenderer:1");
    }
}
