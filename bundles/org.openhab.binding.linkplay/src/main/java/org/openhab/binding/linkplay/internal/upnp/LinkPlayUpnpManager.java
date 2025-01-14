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

    // Timing constants
    private static final Duration SUBSCRIPTION_RENEWAL_PERIOD = Duration.ofMinutes(25);
    private static final Duration SUBSCRIPTION_RETRY_DELAY = Duration.ofSeconds(10);
    private static final Duration SUBSCRIPTION_EXPIRY = Duration.ofMinutes(30);
    private static final int SUBSCRIPTION_DURATION_SECONDS = 1800; // 30 minutes

    private final Object upnpLock = new Object();
    private final Map<String, Instant> subscriptions = new ConcurrentHashMap<>();

    private final UpnpIOService upnpIOService;
    private final LinkPlayDeviceManager deviceManager;
    private final String deviceId;

    private final ScheduledExecutorService scheduler;
    private @Nullable ScheduledFuture<?> subscriptionRenewalFuture;
    private @Nullable String udn;
    private volatile boolean isDisposed = false;

    private static final String SERVICE_AVTRANSPORT       = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String SERVICE_RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1";

    public LinkPlayUpnpManager(UpnpIOService upnpIOService, LinkPlayDeviceManager deviceManager, String deviceId) {
        this.upnpIOService = upnpIOService;
        this.deviceManager = deviceManager;
        this.deviceId = deviceId;
        this.scheduler = ThreadPoolManager.getScheduledPool(LinkPlayBindingConstants.BINDING_ID + "-upnp");
    }

    /**
     * Called once we actually know the device UDN (from config or HTTP).
     * Sets up the participant registration and subscriptions.
     */
    public void register(String udn) {
        if (isDisposed) {
            logger.debug("[{}] Ignoring register request - manager is disposed", deviceId);
            return;
        }
        this.udn = udn;

        try {
            logger.debug("[{}] Registering UPnP participant with UDN: {}", deviceId, udn);
            upnpIOService.registerParticipant(this);

            addSubscription("AVTransport");
            addSubscription("RenderingControl");

            // Indicate to device manager that we have an active subscription
            deviceManager.setUpnpSubscriptionState(true);

            scheduleSubscriptionRenewal();
        } catch (RuntimeException e) {
            logger.warn("[{}] Failed to register UPnP participant: {}", deviceId, e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] Registration error details:", deviceId, e);
            }
            // Try again later
            retryRegistration();
        }
    }

    /**
     * Unregister and clear tracking.
     */
    public void unregister() {
        logger.debug("[{}] Unregistering UPnP participant", deviceId);
        try {
            upnpIOService.unregisterParticipant(this);
            deviceManager.setUpnpSubscriptionState(false);

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
                    upnpIOService.addSubscription(this, fullService, SUBSCRIPTION_DURATION_SECONDS);
                    subscriptions.put(fullService, Instant.now());
                    logger.debug("[{}] Subscribed to service: {}", deviceId, fullService);
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

    private void scheduleSubscriptionRenewal() {
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
                synchronized (upnpLock) {
                    // Remove stale subscriptions
                    subscriptions.entrySet().removeIf(entry -> {
                        Instant lastRenewed = entry.getValue();
                        if (now.isAfter(lastRenewed.plus(SUBSCRIPTION_EXPIRY))) {
                            logger.debug("[{}] Removing expired subscription: {}", deviceId, entry.getKey());
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
                            logger.debug("[{}] Renewed subscription for: {}", deviceId, service);
                        }
                    }
                }
            } catch (RuntimeException e) {
                logger.warn("[{}] Failed to renew UPnP subscriptions: {}", deviceId, e.getMessage());
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
                    logger.debug("[{}] UPnP subscription restored", deviceId);
                } catch (RuntimeException e) {
                    logger.warn("[{}] Retrying UPnP registration failed: {}", deviceId, e.getMessage());
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

        logger.debug("[{}] UPnP event - Service: {}, Variable: {}, Value: {}", deviceId, service, variable, value);

        try {
            switch (service) {
                case SERVICE_AVTRANSPORT:
                    handleAVTransportEvent(variable, value);
                    break;
                case SERVICE_RENDERING_CONTROL:
                    handleRenderingControlEvent(variable, value);
                    break;
                default:
                    logger.trace("[{}] Unknown UPnP service: {}", deviceId, service);
            }
        } catch (Exception e) {
            logger.warn("[{}] Error processing UPnP event: {}", deviceId, e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] Exception detail:", deviceId, e);
            }
        }
    }

    private void handleAVTransportEvent(String variable, String value) {
        switch (variable) {
            case "TransportState":
                deviceManager.updatePlaybackState(value);
                break;
            case "CurrentTrackMetaData":
                if (!value.isEmpty()) {
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
                logger.trace("[{}] Unhandled AVTransport var: {}", deviceId, variable);
        }
    }

    private void handleRenderingControlEvent(String variable, String value) {
        switch (variable) {
            case "Volume":
                deviceManager.updateVolume(value);
                break;
            case "Mute":
                deviceManager.updateMute("1".equals(value));
                break;
            default:
                logger.trace("[{}] Unhandled RenderingControl var: {}", deviceId, variable);
        }
    }

    @Override
    public void onServiceSubscribed(@Nullable String service, boolean succeeded) {
        if (service == null) {
            return;
        }
        if (succeeded) {
            logger.debug("[{}] Successfully subscribed to service: {}", deviceId, service);
            synchronized (upnpLock) {
                subscriptions.put(service, Instant.now());
            }
            deviceManager.setUpnpSubscriptionState(true);
        } else {
            logger.warn("[{}] Failed to subscribe to service: {}", deviceId, service);
            synchronized (upnpLock) {
                subscriptions.remove(service);
            }
            deviceManager.setUpnpSubscriptionState(false);
            retryRegistration();
        }
    }

    @Override
    public @Nullable String getUDN() {
        return udn;
    }

    public void dispose() {
        isDisposed = true;
        logger.debug("[{}] Disposing UPnP manager", deviceId);
        try {
            if (subscriptionRenewalFuture != null) {
                subscriptionRenewalFuture.cancel(true);
                subscriptionRenewalFuture = null;
            }
            unregister();
            scheduler.shutdownNow();
            logger.debug("[{}] UPnP manager disposed", deviceId);
        } catch (RuntimeException e) {
            logger.warn("[{}] Error disposing UPnP manager: {}", deviceId, e.getMessage());
        }
    }

    @Override
    public void onStatusChanged(boolean status) {
        logger.debug("[{}] UPnP device {} is {}", deviceId, getUDN(), (status ? "present" : "absent"));
        // We do not set device offline or online here; HTTP logic does that.
    }

    @Override
    public boolean supportsService(@Nullable String service) {
        return service != null && (service.equals(SERVICE_AVTRANSPORT) || service.equals(SERVICE_RENDERING_CONTROL));
    }
}
