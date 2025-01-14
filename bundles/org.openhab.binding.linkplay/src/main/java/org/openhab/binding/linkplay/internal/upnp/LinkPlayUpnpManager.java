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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.LinkPlayBindingConstants;
import org.openhab.binding.linkplay.internal.handler.LinkPlayDeviceManager;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.io.transport.upnp.UpnpIOParticipant;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LinkPlayUpnpManager} handles UPnP communication with a LinkPlay device.
 * It manages UPnP subscriptions, renewals, and event handling.
 * <p>
 * This class follows a structure similar to other UPnP-based bindings (e.g., Samsung TV),
 * keeping all subscription logic and event processing here. The device-specific business
 * logic (e.g., how to update playback state) is delegated to the {@link LinkPlayDeviceManager}.
 * <p>
 * Note: This class uses {@link DIDLParser} to parse DIDL-Lite or other XML metadata returned
 * by the device in UPnP events.
 * 
 * @author Michael Cumming - Initial Contribution
 */
@NonNullByDefault
public class LinkPlayUpnpManager implements UpnpIOParticipant {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayUpnpManager.class);

    // -------------------
    // Timing Constants
    // -------------------
    private static final Duration SUBSCRIPTION_RENEWAL_PERIOD = Duration.ofMinutes(25);
    private static final Duration SUBSCRIPTION_RETRY_DELAY = Duration.ofSeconds(10);
    private static final Duration SUBSCRIPTION_EXPIRY = Duration.ofMinutes(30);
    private static final int SUBSCRIPTION_DURATION_SECONDS = 1800; // 30 minutes

    /**
     * Lock used for subscription operations that must be atomic.
     */
    private final Object upnpLock = new Object();

    /**
     * Tracks currently subscribed services (full URN) and the last time we renewed them.
     * This single map replaces the need for multiple subscription/renewal maps.
     */
    private final Map<String, Instant> subscriptions = new ConcurrentHashMap<>();

    private final UpnpIOService upnpIOService;
    private final LinkPlayDeviceManager deviceManager;
    private final String deviceId;

    // Thread pool for scheduling renewals/retries
    private final ScheduledExecutorService scheduler;

    // Periodic renewal task
    private @Nullable ScheduledFuture<?> subscriptionRenewalFuture;

    // Unique Device Name (UDN)
    private @Nullable String udn;

    private volatile boolean isDisposed = false;

    // Common UPnP service IDs
    private static final String SERVICE_AVTRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String SERVICE_RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1";

    /**
     * Constructs a new {@link LinkPlayUpnpManager}.
     *
     * @param upnpIOService The UPnP I/O service
     * @param deviceManager The device manager used for updating device state
     * @param deviceId      A human-readable identifier (for logging)
     */
    public LinkPlayUpnpManager(UpnpIOService upnpIOService, LinkPlayDeviceManager deviceManager, String deviceId) {
        this.upnpIOService = upnpIOService;
        this.deviceManager = deviceManager;
        this.deviceId = deviceId;

        // Scheduler thread pool, following openHAB patterns (e.g., Samsung TV binding)
        this.scheduler = ThreadPoolManager.getScheduledPool(LinkPlayBindingConstants.BINDING_ID + "-upnp");
    }

    /**
     * Registers the device for UPnP communication and sets up initial subscriptions
     * for AVTransport and RenderingControl.
     *
     * @param udn The UPnP Unique Device Name
     */
    public void register(String udn) {
        if (isDisposed) {
            logger.debug("[{}] Ignoring registration request - manager is disposed", deviceId);
            return;
        }

        this.udn = udn;
        try {
            // Register this manager as a UPnP participant
            upnpIOService.registerParticipant(this);

            // Attempt to subscribe to required UPnP services
            addSubscription("AVTransport");
            addSubscription("RenderingControl");

            deviceManager.setUpnpSubscriptionState(true);
            logger.debug("[{}] Registered UPnP participant with UDN: {}", deviceId, udn);

            // Schedule periodic renewal
            scheduleSubscriptionRenewal();
        } catch (RuntimeException e) {
            logger.warn("[{}] Failed to register UPnP participant: {}", deviceId, e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] Registration error details:", deviceId, e);
            }
            retryRegistration();
        }
    }

    /**
     * Unregisters this device from UPnP communication, removing subscriptions
     * and clearing internal tracking. 
     */
    public void unregister() {
        logger.debug("[{}] Unregistering UPnP participant", deviceId);
        try {
            upnpIOService.unregisterParticipant(this);
            deviceManager.setUpnpSubscriptionState(false);

            // Clear out the subscription map and UDN
            synchronized (upnpLock) {
                subscriptions.clear();
            }
            udn = null;

            logger.debug("[{}] UPnP participant unregistered", deviceId);
        } catch (RuntimeException e) {
            logger.warn("[{}] Failed to unregister UPnP participant: {}", deviceId, e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] Unregistration error details:", deviceId, e);
            }
        }
    }

    /**
     * Adds a subscription to the given short service name (e.g. "AVTransport").
     * This method automatically constructs the full URN and performs the subscription
     * via UpnpIOService, following openHAB conventions.
     *
     * @param serviceShortName e.g., "AVTransport" or "RenderingControl"
     */
    public void addSubscription(String serviceShortName) {
        if (isDisposed) {
            return;
        }

        String fullService = "urn:schemas-upnp-org:service:" + serviceShortName + ":1";
        synchronized (upnpLock) {
            if (!subscriptions.containsKey(fullService)) {
                // Subscribe for 30 minutes initially
                try {
                    upnpIOService.addSubscription(this, fullService, SUBSCRIPTION_DURATION_SECONDS);
                    subscriptions.put(fullService, Instant.now());
                    logger.debug("[{}] Successfully subscribed to service: {}", deviceId, fullService);
                } catch (RuntimeException e) {
                    logger.warn("[{}] Failed to subscribe to service {}: {}", deviceId, fullService, e.getMessage());
                    if (logger.isDebugEnabled()) {
                        logger.debug("[{}] Subscription error details:", deviceId, e);
                    }
                    retryRegistration();
                }
            } else {
                logger.trace("[{}] Subscription already exists for service: {}", deviceId, fullService);
            }
        }
    }

    /**
     * Periodically renews existing subscriptions, removing any that are expired.
     * Uses a scheduled task to re-subscribe before the expiry window.
     */
    private void scheduleSubscriptionRenewal() {
        // Cancel existing task if it’s still running
        ScheduledFuture<?> future = subscriptionRenewalFuture;
        if (future != null && !future.isCancelled()) {
            future.cancel(true);
        }

        subscriptionRenewalFuture = scheduler.scheduleAtFixedRate(() -> {
            if (isDisposed) {
                return;
            }

            try {
                Instant now = Instant.now();

                // Remove any subscriptions that have fully expired
                synchronized (upnpLock) {
                    subscriptions.entrySet().removeIf(entry -> {
                        Instant lastRenewed = entry.getValue();
                        if (now.isAfter(lastRenewed.plus(SUBSCRIPTION_EXPIRY))) {
                            logger.debug("[{}] Removing expired subscription for service: {}", deviceId, entry.getKey());
                            return true;
                        }
                        return false;
                    });

                    // Renew any subscriptions approaching the renewal period
                    for (Map.Entry<String, Instant> entry : subscriptions.entrySet()) {
                        String service = entry.getKey();
                        Instant lastSubscribed = entry.getValue();

                        if (now.isAfter(lastSubscribed.plus(SUBSCRIPTION_RENEWAL_PERIOD))) {
                            upnpIOService.addSubscription(this, service, SUBSCRIPTION_DURATION_SECONDS);
                            subscriptions.put(service, now);
                            logger.debug("[{}] Renewed UPnP subscription for service: {}", deviceId, service);
                        }
                    }
                }
            } catch (RuntimeException e) {
                logger.warn("[{}] Failed to renew UPnP subscriptions: {}", deviceId, e.getMessage());
                retryRegistration();
            }
        }, SUBSCRIPTION_RENEWAL_PERIOD.toMinutes(), SUBSCRIPTION_RENEWAL_PERIOD.toMinutes(), TimeUnit.MINUTES);
    }

    /**
     * After a failure, schedule a retry of registration/subscription.
     */
    private void retryRegistration() {
        if (isDisposed) {
            return;
        }

        scheduler.schedule(() -> {
            String localUdn = udn;
            if (localUdn != null && subscriptions.isEmpty()) {
                try {
                    upnpIOService.registerParticipant(this);
                    logger.debug("[{}] UPnP subscription restored", deviceId);
                } catch (RuntimeException e) {
                    logger.warn("[{}] Retrying UPnP registration failed: {}", deviceId, e.getMessage());
                    if (logger.isDebugEnabled()) {
                        logger.debug("[{}] Retry error details:", deviceId, e);
                    }
                }
            }
        }, SUBSCRIPTION_RETRY_DELAY.toSeconds(), TimeUnit.SECONDS);
    }

    /**
     * Callback for incoming UPnP events. Each event is identified by 'variable' and 'service'.
     */
    @Override
    public void onValueReceived(@Nullable String variable, @Nullable String value, @Nullable String service) {
        if (isDisposed || variable == null || value == null || service == null) {
            return;
        }

        logger.debug("[{}] UPnP event received - Service: {}, Variable: {}, Value: {}", deviceId, service, variable,
                value);

        try {
            switch (service) {
                case SERVICE_AVTRANSPORT:
                    handleAVTransportEvent(variable, value);
                    break;
                case SERVICE_RENDERING_CONTROL:
                    handleRenderingControlEvent(variable, value);
                    break;
                default:
                    logger.trace("[{}] Ignoring event from unknown service: {}", deviceId, service);
            }
        } catch (Exception e) {
            logger.warn("[{}] Error processing UPnP event: {}", deviceId, e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] Exception detail:", deviceId, e);
            }
        }
    }

    /**
     * Handles AVTransport-specific events (e.g., TransportState, CurrentTrackMetaData, etc.).
     */
    private void handleAVTransportEvent(String variable, String value) {
        logger.trace("[{}] Processing AVTransport event - Variable: {}, Value: {}", deviceId, variable, value);

        switch (variable) {
            case "TransportState":
                deviceManager.updatePlaybackState(value);
                break;
            case "CurrentTrackMetaData":
                if (!value.isEmpty()) {
                    // Use DIDLParser to parse the track metadata
                    Map<String, String> metadata = DIDLParser.parseMetadata(value);
                    if (metadata != null) {
                        deviceManager.updateMetadata(metadata);
                    }
                }
                break;
            case "AVTransportURI":
                deviceManager.updateTransportUri(value);
                break;
            case "CurrentTrackDuration":
                deviceManager.updateDuration(value);
                break;
            default:
                logger.trace("[{}] Unhandled AVTransport variable: {}", deviceId, variable);
        }
    }

    /**
     * Handles RenderingControl-specific events (e.g., Volume, Mute, etc.).
     */
    private void handleRenderingControlEvent(String variable, String value) {
        logger.trace("[{}] Processing RenderingControl event - Variable: {}, Value: {}", deviceId, variable, value);

        switch (variable) {
            case "Volume":
                deviceManager.updateVolume(value);
                break;
            case "Mute":
                deviceManager.updateMute("1".equals(value));
                break;
            default:
                logger.trace("[{}] Unhandled RenderingControl variable: {}", deviceId, variable);
        }
    }

    /**
     * Invoked when a UPnP service is (un)subscribed. We log the result and track
     * success/failure to keep deviceManager informed of state.
     */
    @Override
    public void onServiceSubscribed(@Nullable String service, boolean succeeded) {
        if (service == null) {
            return;
        }

        if (succeeded) {
            logger.debug("[{}] Successfully subscribed to service: {}", deviceId, service);
            synchronized (upnpLock) {
                // Update last-renewed time
                subscriptions.put(service, Instant.now());
            }
            deviceManager.setUpnpSubscriptionState(true);
        } else {
            logger.warn("[{}] Failed to subscribe to service: {}", deviceId, service);
            synchronized (upnpLock) {
                subscriptions.remove(service);
            }
            retryRegistration();
        }
    }

    /**
     * @return the UDN for this participant, used by the UPnP framework.
     */
    @Override
    public @Nullable String getUDN() {
        return udn;
    }

    /**
     * Disposes of this manager, cancelling tasks, unregistering from UPnP, and clearing state.
     */
    public void dispose() {
        isDisposed = true;
        logger.debug("[{}] Disposing UPnP manager", deviceId);

        try {
            // Cancel renewal job if running
            ScheduledFuture<?> future = subscriptionRenewalFuture;
            if (future != null) {
                future.cancel(true);
                subscriptionRenewalFuture = null;
            }

            // Unregister from UPnP to remove subscriptions
            unregister();

            // Shutdown local scheduler
            scheduler.shutdownNow();
            logger.debug("[{}] UPnP manager disposed successfully", deviceId);
        } catch (RuntimeException e) {
            logger.warn("[{}] Error during UPnP manager disposal: {}", deviceId, e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] Disposal error details:", deviceId, e);
            }
        }
    }

    /**
     * Notifies us that the device status has changed at the UPnP layer.
     *
     * @param status true if online/present, false otherwise
     */
    @Override
    public void onStatusChanged(boolean status) {
        if (status) {
            logger.debug("[{}] UPnP device {} is present", deviceId, getUDN());
        } else {
            logger.debug("[{}] UPnP device {} is absent", deviceId, getUDN());
        }
    }

    /**
     * Explicitly register this device as a UPnP participant if not already done.
     *
     * @return true if registration succeeds, false otherwise
     */
    public boolean registerDevice() {
        if (upnpIOService == null) {
            logger.warn("[{}] UpnpIOService not available", deviceId);
            return false;
        }

        try {
            upnpIOService.registerParticipant(this);
            logger.debug("[{}] Successfully registered UPnP device", deviceId);
            return true;
        } catch (Exception e) {
            logger.warn("[{}] Failed to register UPnP device: {}", deviceId, e.getMessage());
            return false;
        }
    }

    /**
     * Determines if we support a given service ID. We only handle AVTransport and RenderingControl
     * in this binding.
     *
     * @param service The full URN of the UPnP service
     * @return true if recognized, false otherwise
     */
    @Override
    public boolean supportsService(@Nullable String service) {
        return service != null
                && (service.equals(SERVICE_AVTRANSPORT) || service.equals(SERVICE_RENDERING_CONTROL));
    }
}
