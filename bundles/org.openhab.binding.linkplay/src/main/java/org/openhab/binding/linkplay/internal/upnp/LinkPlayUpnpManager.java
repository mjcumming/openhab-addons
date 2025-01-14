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
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayUpnpManager implements UpnpIOParticipant {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayUpnpManager.class);

    // Timing constants
    private static final Duration SUBSCRIPTION_RENEWAL_PERIOD = Duration.ofMinutes(25);
    private static final Duration SUBSCRIPTION_RETRY_DELAY = Duration.ofSeconds(10);
    private static final Duration SUBSCRIPTION_EXPIRY = Duration.ofMinutes(30);
    private static final Duration SUBSCRIPTION_DURATION = Duration.ofMinutes(30);
    private static final int SUBSCRIPTION_DURATION_SECONDS = 1800; // 30 minutes

    private final Object upnpLock = new Object();
    private final Map<String, Long> subscriptions = new ConcurrentHashMap<>();
    private final UpnpIOService upnpIOService;
    private final LinkPlayDeviceManager deviceManager;
    private final String deviceId;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Instant> subscriptionTimes;
    private @Nullable ScheduledFuture<?> subscriptionRenewalFuture;

    private @Nullable String udn;
    private volatile boolean isDisposed = false;

    private static final String SERVICE_AVTRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String SERVICE_RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1";

    /**
     * Constructs a new {@link LinkPlayUpnpManager}.
     *
     * @param upnpIOService The UPnP I/O service to use
     * @param deviceManager The device manager this UPnP manager is associated with
     * @param deviceId The device ID for logging and identification
     */
    public LinkPlayUpnpManager(UpnpIOService upnpIOService, LinkPlayDeviceManager deviceManager, String deviceId) {
        this.upnpIOService = upnpIOService;
        this.deviceManager = deviceManager;
        this.deviceId = deviceId;
        this.scheduler = ThreadPoolManager.getScheduledPool(LinkPlayBindingConstants.BINDING_ID + "-upnp");
        this.subscriptionTimes = new ConcurrentHashMap<>();
    }

    /**
     * Registers the device for UPnP communication and sets up initial subscriptions.
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
            upnpIOService.registerParticipant(this);

            // Add subscriptions for required services
            addSubscription("AVTransport");
            addSubscription("RenderingControl");

            deviceManager.setUpnpSubscriptionState(true);
            logger.debug("[{}] Registered UPnP participant with UDN: {}", deviceId, udn);

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
     * Unregisters the device from UPnP communication.
     */
    public void unregister() {
        logger.debug("[{}] Unregistering UPnP participant", deviceId);
        try {
            upnpIOService.unregisterParticipant(this);
            deviceManager.setUpnpSubscriptionState(false);
            subscriptionTimes.clear();
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
     * Schedules periodic renewal of UPnP subscriptions.
     */
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
                // Remove expired subscriptions
                subscriptionTimes.entrySet().removeIf(entry -> {
                    if (now.isAfter(entry.getValue().plus(SUBSCRIPTION_EXPIRY))) {
                        logger.debug("[{}] Removing expired subscription for service: {}", deviceId, entry.getKey());
                        return true;
                    }
                    return false;
                });

                // Renew subscriptions that are due
                for (Map.Entry<String, Instant> entry : subscriptionTimes.entrySet()) {
                    String service = entry.getKey();
                    Instant lastSubscribed = entry.getValue();

                    if (now.isAfter(lastSubscribed.plus(SUBSCRIPTION_RENEWAL_PERIOD))) {
                        upnpIOService.addSubscription(this, service, SUBSCRIPTION_DURATION_SECONDS);
                        subscriptionTimes.put(service, now);
                        logger.debug("[{}] Renewed UPnP subscription for service: {}", deviceId, service);
                    }
                }
            } catch (RuntimeException e) {
                logger.warn("[{}] Failed to renew UPnP subscriptions: {}", deviceId, e.getMessage());
                retryRegistration();
            }
        }, SUBSCRIPTION_RENEWAL_PERIOD.toMinutes(), SUBSCRIPTION_RENEWAL_PERIOD.toMinutes(), TimeUnit.MINUTES);
    }

    /**
     * Retries UPnP registration after a failure.
     */
    private void retryRegistration() {
        if (isDisposed) {
            return;
        }

        scheduler.schedule(() -> {
            String localUdn = udn;
            if (localUdn != null && subscriptionTimes.isEmpty()) {
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

    @Override
    public void onValueReceived(@Nullable String variable, @Nullable String value, @Nullable String service) {
        if (isDisposed || variable == null || value == null || service == null) {
            return;
        }

        logger.debug("[{}] UPnP event received - Service: {}, Variable: {}, Value: {}", 
            deviceId, service, variable, value);

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
        }
    }

    private void handleAVTransportEvent(String variable, String value) {
        if (deviceManager == null) {
            return;
        }

        logger.trace("[{}] Processing AVTransport event - Variable: {}, Value: {}", deviceId, variable, value);

        try {
            switch (variable) {
                case "TransportState":
                    deviceManager.updatePlaybackState(value);
                    break;
                case "CurrentTrackMetaData":
                    if (!value.isEmpty()) {
                        Map<String, String> metadata = DIDLParser.parseMetadataFromXML(value);
                        deviceManager.updateMetadata(metadata);
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
        } catch (Exception e) {
            logger.warn("[{}] Error processing AVTransport event {}: {}", deviceId, variable, e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] AVTransport event error details:", deviceId, e);
            }
        }
    }

    private void handleRenderingControlEvent(String variable, String value) {
        if (deviceManager == null) {
            return;
        }

        logger.trace("[{}] Processing RenderingControl event - Variable: {}, Value: {}", deviceId, variable, value);

        try {
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
        } catch (Exception e) {
            logger.warn("[{}] Error processing RenderingControl event {}: {}", deviceId, variable, e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] RenderingControl event error details:", deviceId, e);
            }
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
                subscriptions.put(service, System.currentTimeMillis());
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

    @Override
    public @Nullable String getUDN() {
        return udn;
    }

    /**
     * Disposes of the UPnP manager resources.
     */
    public void dispose() {
        isDisposed = true;
        logger.debug("[{}] Disposing UPnP manager", deviceId);

        try {
            ScheduledFuture<?> future = subscriptionRenewalFuture;
            if (future != null) {
                future.cancel(true);
                subscriptionRenewalFuture = null;
            }
            unregister();
            subscriptionTimes.clear();
            scheduler.shutdownNow();
            logger.debug("[{}] UPnP manager disposed successfully", deviceId);
        } catch (RuntimeException e) {
            logger.warn("[{}] Error during UPnP manager disposal: {}", deviceId, e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] Disposal error details:", deviceId, e);
            }
        }
    }

    @Override
    public void onStatusChanged(boolean status) {
        if (status) {
            logger.debug("[{}] UPnP device {} is present", deviceId, getUDN());
        } else {
            logger.debug("[{}] UPnP device {} is absent", deviceId, getUDN());
        }
    }

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
     * Subscribes to a UPnP service.
     *
     * @param service The service to subscribe to (e.g. "AVTransport" or "RenderingControl")
     */
    public void addSubscription(String service) {
        if (isDisposed) {
            return;
        }

        synchronized (upnpLock) {
            String fullService = "urn:schemas-upnp-org:service:" + service + ":1";
            try {
                if (!subscriptionTimes.containsKey(fullService)) {
                    upnpIOService.addSubscription(this, fullService, SUBSCRIPTION_DURATION_SECONDS);
                    subscriptionTimes.put(fullService, Instant.now());
                    logger.debug("[{}] Successfully subscribed to service: {}", deviceId, service);
                } else {
                    logger.trace("[{}] Subscription already exists for service: {}", deviceId, service);
                }
            } catch (RuntimeException e) {
                logger.warn("[{}] Failed to subscribe to service {}: {}", deviceId, service, e.getMessage());
                if (logger.isDebugEnabled()) {
                    logger.debug("[{}] Subscription error details:", deviceId, e);
                }
                retryRegistration();
            }
        }
    }

    protected void addSubscription(String serviceId) {
        synchronized (upnpLock) {
            if (subscriptions.containsKey(serviceId)) {
                logger.debug("{} already subscribed to {}", getUDN(), serviceId);
                return;
            }
            subscriptions.put(serviceId, 0L);
            logger.debug("Adding subscription {} for {}, participant is {}", serviceId, getUDN(),
                    upnpIOService.isRegistered(this) ? "registered" : "not registered");
        }
        upnpIOService.addSubscription(this, serviceId, SUBSCRIPTION_DURATION_SECONDS);
    }

    private void removeSubscriptions() {
        logger.debug("Removing subscriptions for {}, participant is {}", getUDN(),
                upnpIOService.isRegistered(this) ? "registered" : "not registered");
        synchronized (upnpLock) {
            subscriptions.forEach((serviceId, lastRenewed) -> {
                logger.debug("Removing subscription for service {}", serviceId);
                upnpIOService.removeSubscription(this, serviceId);
            });
            subscriptions.clear();
        }
    }

    @Override
    public boolean supportsService(@Nullable String service) {
        return service != null && (service.equals(SERVICE_AVTRANSPORT) || service.equals(SERVICE_RENDERING_CONTROL));
    }
}
