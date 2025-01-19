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
package org.openhab.binding.linkplay.internal.transport.upnp;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.LinkPlayBindingConstants;
import org.openhab.binding.linkplay.internal.LinkPlayDeviceManager;
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

    // Timing constants
    private static final Duration SUBSCRIPTION_RENEWAL_PERIOD = Duration.ofMinutes(25);
    private static final Duration SUBSCRIPTION_RETRY_DELAY = Duration.ofSeconds(10);
    private static final Duration SUBSCRIPTION_EXPIRY = Duration.ofMinutes(30);
    private static final int SUBSCRIPTION_DURATION_SECONDS = 1800; // 30 minutes

    private final Object upnpLock = new Object();
    private final Map<String, Instant> subscriptions = new ConcurrentHashMap<>();

    private final UpnpIOService upnpIOService;
    private final LinkPlayDeviceManager deviceManager;

    private final ScheduledExecutorService scheduler;
    private @Nullable ScheduledFuture<?> subscriptionRenewalFuture;
    private @Nullable String udn;
    private volatile boolean isDisposed = false;

    private static final String SERVICE_AVTRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String SERVICE_RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1";

    public LinkPlayUpnpManager(UpnpIOService upnpIOService, LinkPlayDeviceManager deviceManager) {
        this.upnpIOService = upnpIOService;
        this.deviceManager = deviceManager;
        this.scheduler = ThreadPoolManager.getScheduledPool(LinkPlayBindingConstants.BINDING_ID + "-upnp");

        // If we have a UDN in config, use it
        String configUdn = deviceManager.getConfig().getUdn();
        if (!configUdn.isEmpty()) {
            logger.debug("[{}] Registering UPnP device with UDN={}", deviceManager.getConfig().getDeviceName(),
                    configUdn);
            upnpIOService.registerParticipant(this);
        }
    }

    /**
     * Called once we actually know the device UDN (from config or HTTP).
     * Sets up the participant registration and subscriptions.
     */
    public void register(String udn) {
        if (udn.isEmpty()) {
            logger.warn("Cannot register UPnP - UDN is empty");
            return;
        }

        // Normalize UDN format
        String normalizedUdn = udn.startsWith("uuid:") ? udn : "uuid:" + udn;
        logger.debug("Registering UPnP for device with UDN: {}", normalizedUdn);

        // Register with UPnP service asynchronously
        try {
            upnpIOService.registerParticipant(this);
            this.udn = normalizedUdn;

            // Subscribe to events asynchronously
            CompletableFuture.runAsync(() -> {
                try {
                    upnpIOService.addSubscription(this, SERVICE_AVTRANSPORT, SUBSCRIPTION_DURATION_SECONDS);
                    upnpIOService.addSubscription(this, SERVICE_RENDERING_CONTROL, SUBSCRIPTION_DURATION_SECONDS);
                } catch (Exception e) {
                    logger.warn("Failed to subscribe to UPnP events: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            logger.warn("Failed to register UPnP participant: {}", e.getMessage());
        }
    }

    /**
     * Unregister and clear tracking.
     */
    public void unregister() {
        logger.debug("[{}] Unregistering UPnP participant", deviceManager.getConfig().getDeviceName());
        try {
            unregisterUpnpParticipant();
        } catch (RuntimeException e) {
            logger.warn("[{}] Failed to unregister UPnP participant: {}", deviceManager.getConfig().getDeviceName(),
                    e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] Unregistration error details:", deviceManager.getConfig().getDeviceName(), e);
            }
        }
    }

    public void unregisterUpnpParticipant() {
        upnpIOService.unregisterParticipant(this);
        synchronized (upnpLock) {
            subscriptions.clear();
        }
        udn = null;
        logger.debug("[{}] UPnP participant unregistered", deviceManager.getConfig().getDeviceName());
    }

    /**
     * Subscribe to a short-named service ("AVTransport" or "RenderingControl").
     */
    public void addSubscription(String serviceShortName) {
        if (isDisposed) {
            return;
        }

        String fullService = "urn:schemas-upnp-org:service:" + serviceShortName + ":1";
        synchronized (upnpLock) {
            if (!subscriptions.containsKey(fullService)) {
                try {
                    // Verify manager is actually registered
                    if (!upnpIOService.isRegistered(this)) {
                        logger.warn("[{}] Cannot subscribe to {} - device not registered",
                                deviceManager.getConfig().getDeviceName(), serviceShortName);
                        String localUdn = udn;
                        if (localUdn != null && validateDeviceReady(localUdn)) {
                            upnpIOService.addSubscription(this, fullService, SUBSCRIPTION_DURATION_SECONDS);
                            subscriptions.put(fullService, Instant.now());
                            logger.debug("[{}] Subscribed to service: {}", deviceManager.getConfig().getDeviceName(),
                                    fullService);
                        } else {
                            logger.warn("[{}] Device is not ready, retrying subscription registration...",
                                    deviceManager.getConfig().getDeviceName());
                            retryRegistration();
                        }
                        return;
                    }

                    upnpIOService.addSubscription(this, fullService, SUBSCRIPTION_DURATION_SECONDS);
                    subscriptions.put(fullService, Instant.now());
                    logger.debug("[{}] Subscribed to service: {}", deviceManager.getConfig().getDeviceName(),
                            fullService);
                } catch (RuntimeException e) {
                    logger.warn("[{}] Failed to subscribe to service {}: {}", deviceManager.getConfig().getDeviceName(),
                            fullService, e.getMessage());
                    if (logger.isDebugEnabled()) {
                        logger.debug("[{}] Subscription error details:", deviceManager.getConfig().getDeviceName(), e);
                    }
                    retryRegistration();
                }
            } else {
                logger.trace("[{}] Subscription already exists for service: {}",
                        deviceManager.getConfig().getDeviceName(), fullService);
            }
        }
    }

    /**
     * Schedules periodic renewal of UPnP subscriptions to prevent expiration.
     * This is called internally when subscriptions are added or need to be renewed.
     */
    @SuppressWarnings("unused") // Used internally for UPnP subscription management
    private void scheduleSubscriptionRenewal() {
        ScheduledFuture<?> future = subscriptionRenewalFuture;
        if (future != null && !future.isCancelled()) {
            future.cancel(true);
        }

        subscriptionRenewalFuture = scheduler.scheduleWithFixedDelay(() -> {
            if (isDisposed) {
                return;
            }
            try {
                Instant now = Instant.now();
                synchronized (upnpLock) {
                    // Remove stale subscriptions
                    subscriptions.entrySet().removeIf(entry -> {
                        Instant lastRenewed = entry.getValue();
                        if (now.isAfter(lastRenewed.plus(SUBSCRIPTION_EXPIRY))) {
                            logger.debug("[{}] Removing expired subscription: {}",
                                    deviceManager.getConfig().getDeviceName(), entry.getKey());
                            return true;
                        }
                        return false;
                    });

                    // Renew those approaching expiration
                    for (Map.Entry<String, Instant> entry : subscriptions.entrySet()) {
                        String service = entry.getKey();
                        Instant lastSubscribed = entry.getValue();
                        if (now.isAfter(lastSubscribed.plus(SUBSCRIPTION_RENEWAL_PERIOD))) {
                            upnpIOService.addSubscription(this, service, SUBSCRIPTION_DURATION_SECONDS);
                            subscriptions.put(service, now);
                            logger.debug("[{}] Renewed subscription for: {}", deviceManager.getConfig().getDeviceName(),
                                    service);
                        }
                    }
                }
            } catch (RuntimeException e) {
                logger.warn("[{}] Failed to renew UPnP subscriptions: {}", deviceManager.getConfig().getDeviceName(),
                        e.getMessage());
                retryRegistration();
            }
        }, SUBSCRIPTION_RENEWAL_PERIOD.toMinutes(), SUBSCRIPTION_RENEWAL_PERIOD.toMinutes(), TimeUnit.MINUTES);
    }

    private void retryRegistration() {
        if (isDisposed) {
            return;
        }
        scheduler.schedule(() -> {
            String localUdn = udn;
            if (localUdn != null && subscriptions.isEmpty()) {
                try {
                    upnpIOService.registerParticipant(this);
                    logger.debug("[{}] UPnP subscription restored", deviceManager.getConfig().getDeviceName());
                } catch (RuntimeException e) {
                    deviceManager.handleUpnpError("Retrying UPnP registration failed: " + e.getMessage());
                }
            }
        }, SUBSCRIPTION_RETRY_DELAY.toSeconds(), TimeUnit.SECONDS);
    }

    // Called by the Upnp framework for incoming events
    @Override
    public void onValueReceived(@Nullable String variable, @Nullable String value, @Nullable String service) {
        if (isDisposed || variable == null || value == null || service == null) {
            return;
        }

        logger.debug("[{}] UPnP event - Service: {}, Variable: {}, Value: {}",
                deviceManager.getConfig().getDeviceName(), service, variable, value);

        try {
            switch (service) {
                case SERVICE_AVTRANSPORT:
                    processAVTransportUpdate(variable, value);
                    break;
                case SERVICE_RENDERING_CONTROL:
                    processRenderingControlUpdate(variable, value);
                    break;
                default:
                    logger.trace("[{}] Unknown UPnP service: {}", deviceManager.getConfig().getDeviceName(), service);
            }
        } catch (Exception e) {
            logger.warn("[{}] Error processing UPnP event: {}", deviceManager.getConfig().getDeviceName(),
                    e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] Exception detail:", deviceManager.getConfig().getDeviceName(), e);
            }
        }
    }

    private void processAVTransportUpdate(String variable, String value) {
        logger.debug("[{}] Processing AVTransport update - {} : {}", deviceManager.getConfig().getDeviceName(),
                variable, value);

        switch (variable) {
            case "TransportState":
                // TODO: Implement proper channel update mechanism for playback state
                logger.debug("[{}] Received playback state: {}", deviceManager.getConfig().getDeviceName(), value);
                break;
            case "AVTransportURI":
                // TODO: Implement proper channel update mechanism for transport URI
                logger.debug("[{}] Received transport URI: {}", deviceManager.getConfig().getDeviceName(), value);
                break;
            case "CurrentTrackDuration":
                // TODO: Implement proper channel update mechanism for duration
                logger.debug("[{}] Received track duration: {}", deviceManager.getConfig().getDeviceName(), value);
                break;
            case "CurrentTrackMetaData":
                // TODO: Implement proper channel update mechanism for metadata
                logger.debug("[{}] Received track metadata: {}", deviceManager.getConfig().getDeviceName(), value);
                break;
            default:
                logger.debug("[{}] Unhandled AVTransport variable: {}", deviceManager.getConfig().getDeviceName(),
                        variable);
        }
    }

    private void processRenderingControlUpdate(String variable, String value) {
        logger.debug("[{}] Processing RenderingControl update - {} : {}", deviceManager.getConfig().getDeviceName(),
                variable, value);

        if ("Volume".equals(variable)) {
            // TODO: Implement proper channel update mechanism for volume
            logger.debug("[{}] Received volume update: {}", deviceManager.getConfig().getDeviceName(), value);
        } else {
            logger.debug("[{}] Unhandled RenderingControl variable: {}", deviceManager.getConfig().getDeviceName(),
                    variable);
        }
    }

    @Override
    public void onServiceSubscribed(@Nullable String service, boolean succeeded) {
        if (service == null) {
            return;
        }
        if (succeeded) {
            logger.debug("[{}] Successfully subscribed to service: {}", deviceManager.getConfig().getDeviceName(),
                    service);
            synchronized (upnpLock) {
                subscriptions.put(service, Instant.now());
            }
        } else {
            deviceManager.handleUpnpError("Failed to subscribe to service: " + service);
            synchronized (upnpLock) {
                subscriptions.remove(service);
            }
            retryRegistration();
        }
    }

    @Override
    public @Nullable String getUDN() {
        String localUdn = udn;
        return localUdn != null ? localUdn : null;
    }

    /**
     * Dispose of UPnP resources
     */
    public void dispose() {
        isDisposed = true;
        logger.debug("[{}] Disposing UPnP manager", deviceManager.getConfig().getDeviceName());

        // Cancel any scheduled subscription renewal
        ScheduledFuture<?> future = subscriptionRenewalFuture;
        if (future != null) {
            future.cancel(true);
            subscriptionRenewalFuture = null;
        }

        // Unregister from UPnP service
        String udn = getUDN();
        if (udn != null && !udn.isEmpty()) {
            try {
                upnpIOService.unregisterParticipant(this);
                logger.debug("[{}] Unregistered UPnP participant", deviceManager.getConfig().getDeviceName());
            } catch (Exception e) {
                logger.debug("[{}] Error unregistering UPnP participant: {}", deviceManager.getConfig().getDeviceName(),
                        e.getMessage());
            }
        }
    }

    @Override
    public void onStatusChanged(boolean status) {
        String localUdn = getUDN();
        logger.debug("[{}] UPnP device {} is {}", deviceManager.getConfig().getDeviceName(),
                localUdn != null ? localUdn : "<unknown>", (status ? "present" : "absent"));
        // We do not set device offline or online here; HTTP logic does that.
    }

    public boolean supportsService(String service) {
        // Removed redundant null check as 'service' is @NonNull
        return service.equals(SERVICE_AVTRANSPORT) || service.equals(SERVICE_RENDERING_CONTROL);
    }

    private boolean validateDeviceReady(String udn) {
        try {
            return upnpIOService.isRegistered(this);
        } catch (Exception e) {
            logger.debug("[{}] Device validation failed: {}", deviceManager.getConfig().getDeviceName(),
                    e.getMessage());
            return false;
        }
    }
}
