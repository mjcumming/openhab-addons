/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0
 * which is available at http://www.eclipse.org/legal/epl-2.0
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
 * The {@link LinkPlayUpnpManager} handles UPnP discovery, subscriptions, and event callbacks.
 * It retains all existing logic for subscription renewal and dispatches metadata updates.
 * 
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayUpnpManager implements UpnpIOParticipant {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayUpnpManager.class);

    // Subscription intervals
    private static final Duration SUBSCRIPTION_RENEWAL_PERIOD = Duration.ofMinutes(25);
    private static final Duration SUBSCRIPTION_RETRY_DELAY = Duration.ofSeconds(10);
    private static final Duration SUBSCRIPTION_EXPIRY = Duration.ofMinutes(30);
    private static final int SUBSCRIPTION_DURATION_SECONDS = 1800; // 30 min

    private final Object upnpLock = new Object();
    private final Map<String, Instant> subscriptions = new ConcurrentHashMap<>();

    private final UpnpIOService upnpIOService;
    private final LinkPlayDeviceManager deviceManager;
    private final String deviceId;

    private final ScheduledExecutorService scheduler;
    private @Nullable ScheduledFuture<?> subscriptionRenewalFuture;
    private @Nullable String udn;
    private volatile boolean isDisposed = false;

    // We maintain the references to these full-service strings
    private static final String SERVICE_AVTRANSPORT = LinkPlayBindingConstants.UPNP_SERVICE_TYPE_AV_TRANSPORT;
    private static final String SERVICE_RENDERING_CONTROL = LinkPlayBindingConstants.UPNP_SERVICE_TYPE_RENDERING_CONTROL;

    public LinkPlayUpnpManager(UpnpIOService upnpIOService, LinkPlayDeviceManager deviceManager, String deviceId) {
        this.upnpIOService = upnpIOService;
        this.deviceManager = deviceManager;
        this.deviceId = deviceId;

        this.scheduler = ThreadPoolManager.getScheduledPool(LinkPlayBindingConstants.BINDING_ID + "-upnp");
    }

    /**
     * Registers as a UPnP participant if we know the UDN.
     */
    public void register(String udn) {
        if (isDisposed) {
            logger.debug("[{}] Ignoring register call - UpnpManager is disposed", deviceId);
            return;
        }
        this.udn = udn;

        try {
            logger.debug("[{}] Registering UPnP participant with UDN={}", deviceId, udn);
            upnpIOService.registerParticipant(this);

            addSubscription("AVTransport");
            addSubscription("RenderingControl");

            deviceManager.setUpnpSubscriptionState(true);

            scheduleSubscriptionRenewal();
        } catch (RuntimeException e) {
            logger.warn("[{}] Failed to register UPnP participant => {}", deviceId, e.getMessage());
            retryRegistration();
        }
    }

    /**
     * Unregister the participant, clearing all subscriptions.
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
            logger.warn("[{}] Failed to unregister UPnP => {}", deviceId, e.getMessage());
        }
    }

    /**
     * Add subscription by short name, e.g. "AVTransport" => full URN string.
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
                    logger.debug("[{}] Subscribed to service={}", deviceId, fullService);
                } catch (RuntimeException e) {
                    logger.warn("[{}] Failed to subscribe to service={} => {}", deviceId, fullService, e.getMessage());
                    retryRegistration();
                }
            } else {
                logger.trace("[{}] Subscription already exists => {}", deviceId, fullService);
            }
        }
    }

    private void scheduleSubscriptionRenewal() {
        if (subscriptionRenewalFuture != null && !subscriptionRenewalFuture.isCancelled()) {
            subscriptionRenewalFuture.cancel(true);
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
                        boolean expired = now.isAfter(lastRenewed.plus(SUBSCRIPTION_EXPIRY));
                        if (expired) {
                            logger.debug("[{}] Removing expired subscription={}", deviceId, entry.getKey());
                        }
                        return expired;
                    });

                    // Renew those approaching expiration
                    for (Map.Entry<String, Instant> entry : subscriptions.entrySet()) {
                        String service = entry.getKey();
                        Instant lastSubscribed = entry.getValue();
                        if (now.isAfter(lastSubscribed.plus(SUBSCRIPTION_RENEWAL_PERIOD))) {
                            upnpIOService.addSubscription(this, service, SUBSCRIPTION_DURATION_SECONDS);
                            subscriptions.put(service, now);
                            logger.debug("[{}] Renewed subscription={}", deviceId, service);
                        }
                    }
                }
            } catch (RuntimeException e) {
                logger.warn("[{}] Failed to renew UPnP subscriptions => {}", deviceId, e.getMessage());
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
                    logger.debug("[{}] Upnp subscription restored after retry", deviceId);
                } catch (RuntimeException e) {
                    logger.warn("[{}] Retrying Upnp registration failed => {}", deviceId, e.getMessage());
                }
            }
        }, SUBSCRIPTION_RETRY_DELAY.toSeconds(), TimeUnit.SECONDS);
    }

    /**
     * Called by the framework when a UPnP event arrives.
     */
    @Override
    public void onValueReceived(@Nullable String variable, @Nullable String value, @Nullable String service) {
        if (isDisposed || variable == null || value == null || service == null) {
            return;
        }
        logger.debug("[{}] UPnP event => service={}, var={}, value={}", deviceId, service, variable, value);

        try {
            switch (service) {
                case SERVICE_AVTRANSPORT:
                    handleAVTransportEvent(variable, value);
                    break;

                case SERVICE_RENDERING_CONTROL:
                    handleRenderingControlEvent(variable, value);
                    break;

                default:
                    logger.trace("[{}] Unknown UPnP service => {}", deviceId, service);
            }
        } catch (Exception e) {
            logger.warn("[{}] Error processing UPnP event => {}", deviceId, e.getMessage());
        }
    }

    private void handleAVTransportEvent(String variable, String value) {
        switch (variable) {
            case "TransportState":
                deviceManager.updatePlaybackState(value);
                break;

            case "CurrentTrackMetaData":
                if (!value.isEmpty()) {
                    @Nullable
                    Map<String, String> metadata = DIDLParser.parseMetadata(value);
                    if (metadata != null && !metadata.isEmpty()) {
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
                logger.trace("[{}] Unhandled AVTransport variable => {}", deviceId, variable);
        }
    }

    private void handleRenderingControlEvent(String variable, String value) {
        switch (variable) {
            case "Volume":
                deviceManager.updateVolume(value);
                break;

            case "Mute":
                boolean isMute = ("1".equals(value) || "true".equalsIgnoreCase(value));
                deviceManager.updateMute(isMute);
                break;

            default:
                logger.trace("[{}] Unhandled RenderingControl variable => {}", deviceId, variable);
        }
    }

    /**
     * Called by the framework when subscription is confirmed or fails.
     */
    @Override
    public void onServiceSubscribed(@Nullable String service, boolean succeeded) {
        if (service == null) {
            return;
        }
        if (succeeded) {
            logger.debug("[{}] Successfully subscribed => {}", deviceId, service);
            synchronized (upnpLock) {
                subscriptions.put(service, Instant.now());
            }
            deviceManager.setUpnpSubscriptionState(true);
        } else {
            logger.warn("[{}] Failed to subscribe => {}", deviceId, service);
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

    @Override
    public void onStatusChanged(boolean status) {
        logger.debug("[{}] UPnP device {} is {}", deviceId, (udn != null ? udn : "<unknown>"),
                (status ? "present" : "absent"));
        // Typically we do not set OFFLINE here, letting the HTTP logic do that
    }

    /**
     * Dispose logic, stopping the subscription renewal and unsubscribing.
     */
    public void dispose() {
        isDisposed = true;
        logger.debug("[{}] Disposing UpnpManager", deviceId);

        if (subscriptionRenewalFuture != null) {
            subscriptionRenewalFuture.cancel(true);
            subscriptionRenewalFuture = null;
        }

        unregister();
        scheduler.shutdownNow();
    }

    public boolean supportsService(String service) {
        // Check if service is AVTransport or RenderingControl
        return service.equals(SERVICE_AVTRANSPORT) || service.equals(SERVICE_RENDERING_CONTROL);
    }
}
