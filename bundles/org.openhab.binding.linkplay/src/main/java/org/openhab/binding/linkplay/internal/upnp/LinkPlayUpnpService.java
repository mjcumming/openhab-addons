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
package org.openhab.binding.linkplay.internal.upnp;

import static org.openhab.binding.linkplay.internal.LinkPlayBindingConstants.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.io.transport.upnp.UpnpIOParticipant;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles UPnP subscriptions and event processing for a LinkPlay device.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayUpnpService implements UpnpIOParticipant {
    private final Logger logger = LoggerFactory.getLogger(LinkPlayUpnpService.class);
    private final ReentrantLock subscriptionLock = new ReentrantLock();

    private final String udn;
    private final UpnpIOService upnpIOService;
    private final ScheduledExecutorService scheduler;
    private final LinkPlayEventListener eventListener;

    private @Nullable ScheduledFuture<?> renewSubscriptionFuture;
    private boolean isSubscribed = false;
    private static final int SUBSCRIPTION_DURATION = 600; // 10 minutes
    private static final int RENEWAL_PERIOD = 540; // 9 minutes

    private final Map<String, Instant> lastEventMap = new ConcurrentHashMap<>();
    private @Nullable ScheduledFuture<?> eventMonitorJob;

    private static final int EVENT_MONITOR_INTERVAL = 30; // seconds
    private static final int EVENT_EXPIRATION_TIME = 60; // seconds
    private static final int MAX_SUBSCRIPTION_RETRIES = 3;
    private static final int MAX_SYNC_RETRIES = 3;
    private static final int SYNC_RETRY_DELAY = 10; // seconds

    private enum SubscriptionState {
        INIT,
        SUBSCRIBING,
        SUBSCRIBED,
        FAILED,
        UNSUBSCRIBED
    }

    private final Map<String, SubscriptionState> subscriptionStates = new ConcurrentHashMap<>();

    private volatile boolean isConnected = false;
    private @Nullable ScheduledFuture<?> connectionTrackerJob;
    private static final int CONNECTION_CHECK_INTERVAL = 60; // seconds

    private static final Set<String> SUPPORTED_SERVICES = Set.of(UPNP_SERVICE_TYPE_AV_TRANSPORT,
            UPNP_SERVICE_TYPE_RENDERING_CONTROL);

    private long lastEventTimestamp = 0;
    private static final long HEALTH_CHECK_INTERVAL = TimeUnit.MINUTES.toMillis(5);

    public LinkPlayUpnpService(String udn, UpnpIOService upnpIOService, ScheduledExecutorService scheduler,
            LinkPlayEventListener eventListener) {
        this.udn = udn;
        this.upnpIOService = upnpIOService;
        this.scheduler = scheduler;
        this.eventListener = eventListener;
    }

    public void start() {
        logger.debug("Starting UPnP service for device {}", udn);
        subscribeToServices();
        startEventMonitor();
        startConnectionTracker();
    }

    public void stop() {
        subscriptionLock.lock();
        try {
            logger.debug("Stopping UPnP service for device {}", udn);
            stopEventMonitor();
            stopConnectionTracker();
            cancelRenewal();

            if (isSubscribed) {
                for (String service : subscriptionStates.keySet()) {
                    try {
                        upnpIOService.removeSubscription(this, service);
                        subscriptionStates.put(service, SubscriptionState.UNSUBSCRIBED);
                    } catch (Exception e) {
                        logger.warn("Error unsubscribing from service {}: {}", service, e.getMessage());
                    }
                }
                upnpIOService.unregisterParticipant(this);
                isSubscribed = false;
            }
        } finally {
            subscriptionLock.unlock();
        }
    }

    private void subscribeToServices() {
        subscriptionLock.lock();
        try {
            if (!isSubscribed) {
                logger.debug("Subscribing to UPnP services for device {}", udn);
                upnpIOService.registerParticipant(this);

                // Subscribe to AVTransport
                subscribeToService(UPNP_SERVICE_TYPE_AV_TRANSPORT);

                // Subscribe to RenderingControl
                subscribeToService(UPNP_SERVICE_TYPE_RENDERING_CONTROL);

                scheduleRenewal();
                isSubscribed = true;
            }
        } finally {
            subscriptionLock.unlock();
        }
    }

    private void unsubscribeFromServices() {
        subscriptionLock.lock();
        try {
            if (isSubscribed) {
                logger.debug("Unsubscribing from UPnP services for device {}", udn);
                cancelRenewal();

                upnpIOService.unregisterParticipant(this);
                isSubscribed = false;
            }
        } finally {
            subscriptionLock.unlock();
        }
    }

    private void subscribeToService(String serviceId) {
        subscribeToService(serviceId, 0);
    }

    private void subscribeToService(String serviceId, int retryCount) {
        try {
            subscriptionStates.put(serviceId, SubscriptionState.SUBSCRIBING);
            logger.debug("Subscribing to service {} for device {} (retry {})", serviceId, udn, retryCount);

            upnpIOService.addSubscription(this, serviceId, SUBSCRIPTION_DURATION);
            lastEventMap.put(serviceId, Instant.now());

        } catch (Exception e) {
            subscriptionStates.put(serviceId, SubscriptionState.FAILED);
            logger.warn("Subscription failed for service {} (retry {}): {} - {}", serviceId, retryCount,
                    e.getClass().getSimpleName(), e.getMessage());

            if (retryCount < MAX_SUBSCRIPTION_RETRIES) {
                int delay = (retryCount + 1) * 10; // Exponential backoff
                logger.debug("Scheduling retry {} in {} seconds for service {}", retryCount + 1, delay, serviceId);

                scheduler.schedule(() -> subscribeToService(serviceId, retryCount + 1), delay, TimeUnit.SECONDS);
            } else {
                logger.error("Failed to subscribe to service {} after {} attempts. Last error: {}", serviceId,
                        MAX_SUBSCRIPTION_RETRIES, e.getMessage());
            }
        }
    }

    private void scheduleRenewal() {
        cancelRenewal();
        renewSubscriptionFuture = scheduler.scheduleWithFixedDelay(this::renewSubscriptions, RENEWAL_PERIOD,
                RENEWAL_PERIOD, TimeUnit.SECONDS);
    }

    private void cancelRenewal() {
        ScheduledFuture<?> future = renewSubscriptionFuture;
        if (future != null) {
            future.cancel(true);
            renewSubscriptionFuture = null;
        }
    }

    private void renewSubscriptions() {
        subscriptionLock.lock();
        try {
            if (isSubscribed) {
                logger.debug("Renewing UPnP subscriptions for device {}", udn);
                subscribeToService(UPNP_SERVICE_TYPE_AV_TRANSPORT);
                subscribeToService(UPNP_SERVICE_TYPE_RENDERING_CONTROL);
            }
        } catch (Exception e) {
            logger.warn("Failed to renew UPnP subscriptions for device {}: {}", udn, e.getMessage());
        } finally {
            subscriptionLock.unlock();
        }
    }

    private void startEventMonitor() {
        stopEventMonitor();
        eventMonitorJob = scheduler.scheduleWithFixedDelay(this::monitorEvents, EVENT_MONITOR_INTERVAL,
                EVENT_MONITOR_INTERVAL, TimeUnit.SECONDS);
    }

    private void stopEventMonitor() {
        ScheduledFuture<?> job = eventMonitorJob;
        if (job != null) {
            job.cancel(true);
            eventMonitorJob = null;
        }
    }

    private void monitorEvents() {
        Instant now = Instant.now();
        boolean needsResubscription = false;

        for (Map.Entry<String, Instant> entry : lastEventMap.entrySet()) {
            String service = entry.getKey();
            Instant lastEvent = entry.getValue();

            if (lastEvent.plusSeconds(EVENT_EXPIRATION_TIME).isBefore(now)) {
                logger.debug("No events received for service {} in {} seconds, scheduling resubscription", service,
                        EVENT_EXPIRATION_TIME);
                needsResubscription = true;
            }
        }

        if (needsResubscription) {
            resubscribeToServices();
        }
    }

    private void resubscribeToServices() {
        subscriptionLock.lock();
        try {
            logger.debug("Resubscribing to UPnP services for device {}", udn);
            unsubscribeFromServices();

            // Brief delay before resubscribing
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            subscribeToServices();
        } finally {
            subscriptionLock.unlock();
        }
    }

    @Override
    public String getUDN() {
        return udn;
    }

    @Override
    public void onValueReceived(@Nullable String variable, @Nullable String value, @Nullable String service) {
        if (variable == null || value == null || service == null) {
            logger.warn("Received invalid UPnP event - Variable: {}, Value: {}, Service: {}", variable, value, service);
            return;
        }

        if (!isValidService(service)) {
            return;
        }

        // Update last event timestamp and verify subscription state
        lastEventMap.put(service, Instant.now());
        subscriptionStates.putIfAbsent(service, SubscriptionState.SUBSCRIBED);

        logger.debug("UPnP event received - Device: {}, Service: {}, Variable: {}, Value: {}", udn, service, variable,
                value);

        try {
            eventListener.onEventReceived(service, variable, value);
        } catch (Exception e) {
            logger.warn("Error processing UPnP event - Service: {}, Variable: {}: {}", service, variable,
                    e.getMessage());
        }

        lastEventTimestamp = System.currentTimeMillis();
    }

    @Override
    public void onServiceSubscribed(@Nullable String service, boolean succeeded) {
        if (service != null) {
            if (succeeded) {
                subscriptionStates.put(service, SubscriptionState.SUBSCRIBED);
                logger.debug("Successfully subscribed to service {} for device {}", service, udn);
                lastEventMap.put(service, Instant.now());
            } else {
                subscriptionStates.put(service, SubscriptionState.FAILED);
                logger.warn("Failed to subscribe to service {} for device {}", service, udn);
                // Retry with backoff
                scheduler.schedule(() -> subscribeToService(service), 30, TimeUnit.SECONDS);
            }
        }
    }

    public void synchronizeState(int retryCount) {
        if (retryCount >= MAX_SYNC_RETRIES) {
            logger.warn("Failed to synchronize state after {} attempts for device {}", MAX_SYNC_RETRIES, udn);
            return;
        }

        try {
            // Attempt state synchronization
            if (isSubscribed) {
                logger.debug("Synchronizing UPnP state for device {} (attempt {})", udn, retryCount + 1);

                // Request current states through event listener
                eventListener.onEventReceived(UPNP_SERVICE_TYPE_AV_TRANSPORT, "GetState", "");
                eventListener.onEventReceived(UPNP_SERVICE_TYPE_RENDERING_CONTROL, "GetState", "");

                // Update last event timestamps
                lastEventMap.put(UPNP_SERVICE_TYPE_AV_TRANSPORT, Instant.now());
                lastEventMap.put(UPNP_SERVICE_TYPE_RENDERING_CONTROL, Instant.now());
            }
        } catch (Exception e) {
            logger.warn("Error synchronizing state (attempt {}): {}", retryCount + 1, e.getMessage());
            // Schedule retry with exponential backoff
            scheduler.schedule(() -> synchronizeState(retryCount + 1), SYNC_RETRY_DELAY * (retryCount + 1),
                    TimeUnit.SECONDS);
        }
    }

    public Map<String, SubscriptionState> getSubscriptionStates() {
        return new HashMap<>(subscriptionStates);
    }

    private void startConnectionTracker() {
        stopConnectionTracker();
        connectionTrackerJob = scheduler.scheduleWithFixedDelay(this::checkConnection, 0, CONNECTION_CHECK_INTERVAL,
                TimeUnit.SECONDS);
    }

    private void stopConnectionTracker() {
        ScheduledFuture<?> job = connectionTrackerJob;
        if (job != null) {
            job.cancel(true);
            connectionTrackerJob = null;
        }
    }

    private void checkConnection() {
        try {
            upnpIOService.isRegistered(this);

            if (!isConnected) {
                logger.debug("UPnP connection restored for device {}", udn);
                isConnected = true;
                if (isHealthy()) {
                    resubscribeToServices();
                }
            }
        } catch (Exception e) {
            if (isConnected) {
                logger.warn("UPnP connection lost for device {}: {}", udn, e.getMessage());
                isConnected = false;
            }
        }
    }

    private boolean isValidService(String service) {
        if (!SUPPORTED_SERVICES.contains(service)) {
            logger.debug("Unsupported UPnP service {} for device {}", service, udn);
            return false;
        }
        return true;
    }

    private boolean isHealthy() {
        if (!isConnected) {
            return false;
        }

        // Check if we've received any events recently
        long now = System.currentTimeMillis();
        if (lastEventTimestamp > 0 && (now - lastEventTimestamp) > HEALTH_CHECK_INTERVAL) {
            logger.debug("No UPnP events received for device {} in the last {} minutes", udn,
                    TimeUnit.MILLISECONDS.toMinutes(HEALTH_CHECK_INTERVAL));
            return false;
        }

        return true;
    }

    @Override
    public void onStatusChanged(boolean status) {
        logger.debug("UPnP status changed to {} for device {}", status, udn);
        isConnected = status;

        if (status) {
            // Resubscribe when connection is restored
            resubscribeToServices();
        }
    }
}
