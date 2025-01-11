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
package org.openhab.binding.linkplay.internal.handler;

import static org.openhab.binding.linkplay.internal.LinkPlayBindingConstants.*;

import java.time.Instant;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.binding.linkplay.internal.http.LinkPlayApiException;
import org.openhab.binding.linkplay.internal.http.LinkPlayCommunicationException;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpClient;
import org.openhab.binding.linkplay.internal.model.PlayerStatus;
import org.openhab.core.io.transport.upnp.UpnpIOParticipant;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.library.types.NextPreviousType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.PlayPauseType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LinkPlayThingHandler} is responsible for handling commands and status
 * updates for LinkPlay devices.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayThingHandler extends BaseThingHandler implements UpnpIOParticipant {
    private final Logger logger = LoggerFactory.getLogger(LinkPlayThingHandler.class);
    private final LinkPlayHttpClient httpClient;
    private final LinkPlayGroupManager groupManager;
    private final UpnpIOService upnpIOService;
    private final Object upnpLock = new Object();
    private @Nullable ScheduledFuture<?> pollingJob;
    private int pollingInterval;
    private int reconnectCount;
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final int SUBSCRIPTION_DURATION = 1800; // 30 minutes
    private Map<String, Instant> subscriptions = new ConcurrentHashMap<>();

    public LinkPlayThingHandler(Thing thing, LinkPlayHttpClient httpClient, UpnpIOService upnpIOService) {
        super(thing);
        this.httpClient = httpClient;
        this.upnpIOService = upnpIOService;
        this.groupManager = new LinkPlayGroupManager(thing, httpClient);
    }

    @Override
    public void initialize() {
        LinkPlayConfiguration config = getConfigAs(LinkPlayConfiguration.class);
        if (!config.isValid()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Invalid configuration (check IP address)");
            return;
        }

        pollingInterval = config.pollingInterval;
        reconnectCount = 0;

        // Register UPnP participant
        logger.debug("[{}] Registering UPnP participant", getThing().getUID());
        upnpIOService.registerParticipant(this);

        // Initialize group manager right away since it's just a helper class
        groupManager.initialize(config.ipAddress);

        // Start polling for device status
        startPolling();

        // Initial status will be set by the first poll
        updateStatus(ThingStatus.UNKNOWN);
    }

    private void handleInitializationError(String message, Exception e) {
        logger.warn("{}: {}", message, e.getMessage());
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, message);
    }

    private void initializeDevice(String ipAddress) {
        // Initialize group manager right away
        groupManager.initialize(ipAddress);

        // Start polling for device status
        startPolling();

        // Add UPnP subscriptions
        addSubscription(UPNP_SERVICE_TYPE_AV_TRANSPORT);
        addSubscription(UPNP_SERVICE_TYPE_RENDERING_CONTROL);

        // Force initial status check regardless of linked channels
        logger.debug("[{}] Performing initial device status check", thing.getUID());
        httpClient.getStatusEx(ipAddress).thenAccept(status -> {
            logger.debug("[{}] Initial status check successful", thing.getUID());
            updateStatus(ThingStatus.ONLINE);
        }).exceptionally(e -> {
            logger.debug("[{}] Initial status check failed: {}", thing.getUID(), e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Initial status check failed: " + e.getMessage());
            return null;
        });
    }

    private void tryInitialize(String ipAddress, int attempt) {
        if (attempt >= MAX_RECONNECT_ATTEMPTS) {
            handleInitializationError("Failed to initialize device",
                    new LinkPlayCommunicationException("Max retry attempts reached"));
            return;
        }

        // First check if device is responsive by getting its status
        httpClient.getStatusEx(ipAddress).thenAccept(status -> {
            groupManager.initialize(ipAddress);
            startPolling();
            // Add UPnP subscriptions
            addSubscription(UPNP_SERVICE_TYPE_AV_TRANSPORT);
            addSubscription(UPNP_SERVICE_TYPE_RENDERING_CONTROL);
            updateStatus(ThingStatus.ONLINE);
        }).exceptionally(e -> {
            logger.debug("Initialization attempt {} failed: {}", attempt + 1, e.getMessage());
            // Schedule retry after delay
            scheduler.schedule(() -> tryInitialize(ipAddress, attempt + 1), 10, TimeUnit.SECONDS);
            return null;
        });
    }

    protected boolean isUpnpDeviceRegistered() {
        return upnpIOService.isRegistered(this);
    }

    protected void addSubscription(String serviceId) {
        synchronized (upnpLock) {
            if (subscriptions.containsKey(serviceId)) {
                logger.debug("{} already subscribed to {}", getUDN(), serviceId);
                return;
            }
            subscriptions.put(serviceId, Instant.MIN);
            logger.debug("Adding GENA subscription {} for {}, participant is {}", serviceId, getUDN(),
                    isUpnpDeviceRegistered() ? "registered" : "not registered");
        }
        if (isUpnpDeviceRegistered()) {
            upnpIOService.addSubscription(this, serviceId, SUBSCRIPTION_DURATION);
        } else {
            logger.debug("Not adding subscription {} - participant not registered", serviceId);
        }
    }

    private void removeSubscriptions() {
        logger.debug("Removing subscriptions for {}", getUDN());
        synchronized (upnpLock) {
            subscriptions.forEach((serviceId, lastRenewed) -> {
                logger.debug("Removing subscription for service {}", serviceId);
                upnpIOService.removeSubscription(this, serviceId);
            });
            subscriptions.clear();
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (thing.getStatus() != ThingStatus.ONLINE) {
            logger.debug("Thing not ONLINE, ignoring command {}", command);
            return;
        }

        if (command instanceof RefreshType) {
            handleRefresh(channelUID);
            return;
        }

        String groupId = channelUID.getGroupId();
        if (groupId == null) {
            logger.debug("No group ID for channel {}", channelUID);
            return;
        }

        try {
            switch (groupId) {
                case GROUP_PLAYBACK:
                    handlePlaybackCommand(channelUID, command);
                    break;
                case GROUP_MULTIROOM:
                    groupManager.handleCommand(channelUID, command);
                    break;
                default:
                    logger.debug("Unknown channel group: {}", groupId);
            }
        } catch (Exception e) {
            handleCommandError("Error handling command", channelUID, command, e);
        }
    }

    private void handleCommandError(String message, ChannelUID channelUID, Command command, Exception e) {
        if (e instanceof LinkPlayCommunicationException) {
            logger.warn("{} {} for channel {}: {}", message, command, channelUID, e.getMessage());
            handleCommunicationError(e);
        } else if (e instanceof LinkPlayApiException) {
            logger.debug("{} {} for channel {}: {}", message, command, channelUID, e.getMessage());
        } else {
            logger.warn("{} {} for channel {}: {}", message, command, channelUID, e.getMessage());
        }
    }

    protected void handleCommunicationError(Exception e) {
        // Only update status if we're not already offline
        if (thing.getStatus() != ThingStatus.OFFLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Device not reachable: " + e.getMessage());
        }
    }

    private void handleRefresh(ChannelUID channelUID) {
        String groupId = channelUID.getGroupId();
        if (groupId == null) {
            return;
        }

        Set<String> channels = Set.of(channelUID.getId());
        pollDeviceState(channels);
    }

    private void startPolling() {
        if (pollingJob != null) {
            pollingJob.cancel(true);
        }

        pollingJob = scheduler.scheduleWithFixedDelay(() -> {
            try {
                LinkPlayConfiguration config = getConfigAs(LinkPlayConfiguration.class);
                pollDeviceStatus(config.ipAddress);
            } catch (Exception e) {
                logger.debug("[{}] Error during polling: {}", getThing().getUID(), e.getMessage());
            }
        }, 0, pollingInterval, TimeUnit.SECONDS);
    }

    private void pollDeviceStatus(String ipAddress) {
        logger.debug("[{}] Polling device status", getThing().getUID());

        httpClient.getStatusEx(ipAddress).thenAccept(status -> {
            if (getThing().getStatus() != ThingStatus.ONLINE) {
                logger.debug("[{}] Device is responsive, marking ONLINE", getThing().getUID());
                updateStatus(ThingStatus.ONLINE);

                // Add UPnP subscriptions only when device is confirmed responsive
                addSubscription(UPNP_SERVICE_TYPE_AV_TRANSPORT);
                addSubscription(UPNP_SERVICE_TYPE_RENDERING_CONTROL);
            }

            // Update channels with status info
            updateState("playback#title", new StringType(status.get("Title").getAsString()));
            // Add other channel updates as needed
        }).exceptionally(e -> {
            if (getThing().getStatus() == ThingStatus.ONLINE) {
                logger.debug("[{}] Device is not responding, marking OFFLINE", getThing().getUID());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Device not responding: " + e.getMessage());
            }
            return null;
        });
    }

    private void pollDeviceState(Set<String> channels) {
        String ipAddress = getConfigAs(LinkPlayConfiguration.class).ipAddress;
        logger.debug("[{}] Polling device state for channels: {}", thing.getUID(), channels);

        if (channels.stream().anyMatch(id -> id.startsWith(GROUP_PLAYBACK))) {
            httpClient.getStatusEx(ipAddress).thenAccept(status -> {
                logger.debug("[{}] Status check successful", thing.getUID());
                // Device responded successfully
                if (thing.getStatus() != ThingStatus.ONLINE) {
                    updateStatus(ThingStatus.ONLINE);
                }

                // Get detailed status and update channels
                httpClient.getPlayerStatus(ipAddress).thenAccept(playerStatus -> {
                    logger.debug("[{}] Updating playback channels", thing.getUID());
                    updatePlaybackChannels(playerStatus);
                }).exceptionally(e -> {
                    logger.debug("[{}] Error getting player status: {}", thing.getUID(), e.getMessage());
                    return null;
                });
            }).exceptionally(e -> {
                logger.debug("[{}] Error polling device status: {}", thing.getUID(), e.getMessage());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Device not responding: " + e.getMessage());
                return null;
            });
        }

        if (channels.stream().anyMatch(id -> id.startsWith(GROUP_MULTIROOM))) {
            logger.debug("[{}] Updating group state", thing.getUID());
            groupManager.updateGroupState();
        }
    }

    private void handlePollError(String operation, Throwable e) {
        Throwable cause = e.getCause();
        if (cause instanceof LinkPlayCommunicationException) {
            handleCommunicationError((LinkPlayCommunicationException) cause);
        } else {
            logger.debug("Error {} : {}", operation, e.getMessage());
        }
    }

    @Override
    public void dispose() {
        stopPolling();
        removeSubscriptions();
        logger.debug("Unregistering UPnP participant for {}", getThing().getUID());
        upnpIOService.unregisterParticipant(this);
        super.dispose();
    }

    private void stopPolling() {
        ScheduledFuture<?> job = pollingJob;
        if (job != null && !job.isCancelled()) {
            job.cancel(true);
            pollingJob = null;
        }
    }

    private void handlePlaybackCommand(ChannelUID channelUID, Command command) {
        String ipAddress = getConfigAs(LinkPlayConfiguration.class).ipAddress;
        String channelId = channelUID.getIdWithoutGroup();

        try {
            switch (channelId) {
                case CHANNEL_CONTROL:
                    if (command instanceof PlayPauseType) {
                        if (command == PlayPauseType.PLAY) {
                            httpClient.sendCommand(ipAddress, "setPlayerCmd:play");
                        } else if (command == PlayPauseType.PAUSE) {
                            httpClient.sendCommand(ipAddress, "setPlayerCmd:pause");
                        }
                    } else if (command instanceof NextPreviousType) {
                        if (command == NextPreviousType.NEXT) {
                            httpClient.sendCommand(ipAddress, "setPlayerCmd:next");
                        } else if (command == NextPreviousType.PREVIOUS) {
                            httpClient.sendCommand(ipAddress, "setPlayerCmd:prev");
                        }
                    }
                    break;

                case CHANNEL_VOLUME:
                    if (command instanceof PercentType) {
                        int volume = ((PercentType) command).intValue();
                        httpClient.sendCommand(ipAddress, String.format("setPlayerCmd:vol:%d", volume));
                    }
                    break;

                case CHANNEL_MUTE:
                    if (command instanceof OnOffType) {
                        boolean mute = command == OnOffType.ON;
                        httpClient.sendCommand(ipAddress, String.format("setPlayerCmd:mute:%d", mute ? 1 : 0));
                    }
                    break;

                default:
                    logger.debug("Channel {} not handled", channelId);
            }
        } catch (Exception e) {
            handleCommandError("playback command", channelUID, command, e);
        }
    }

    protected void updatePlaybackChannels(PlayerStatus status) {
        updateState(new ChannelUID(thing.getUID(), GROUP_PLAYBACK, CHANNEL_CONTROL),
                "play".equals(status.getPlayStatus()) ? PlayPauseType.PLAY : PlayPauseType.PAUSE);
        updateState(new ChannelUID(thing.getUID(), GROUP_PLAYBACK, CHANNEL_VOLUME),
                new PercentType(status.getVolume()));
        updateState(new ChannelUID(thing.getUID(), GROUP_PLAYBACK, CHANNEL_MUTE), OnOffType.from(status.isMute()));
    }

    protected void updateGroupChannels(String role, String masterIP, String slaveIPs) {
        updateState(new ChannelUID(thing.getUID(), GROUP_MULTIROOM, CHANNEL_ROLE), new StringType(role));
        updateState(new ChannelUID(thing.getUID(), GROUP_MULTIROOM, CHANNEL_MASTER_IP), new StringType(masterIP));
        updateState(new ChannelUID(thing.getUID(), GROUP_MULTIROOM, CHANNEL_SLAVE_IPS), new StringType(slaveIPs));
    }

    @Override
    public String getUDN() {
        return getThing().getUID().toString();
    }

    @Override
    public void onValueReceived(@Nullable String variable, @Nullable String value, @Nullable String service) {
        if (variable == null || value == null || service == null) {
            return;
        }
        logger.debug("Received UPnP value: {} = {} for service {}", variable, value, service);
        // Handle UPnP events based on service type
        switch (service) {
            case UPNP_SERVICE_TYPE_AV_TRANSPORT:
                handleAVTransportEvent(variable, value);
                break;
            case UPNP_SERVICE_TYPE_RENDERING_CONTROL:
                handleRenderingControlEvent(variable, value);
                break;
            default:
                logger.debug("Unhandled UPnP service: {}", service);
        }
    }

    @Override
    public void onServiceSubscribed(@Nullable String service, boolean succeeded) {
        if (service == null) {
            return;
        }
        logger.debug("Subscription to service {} for {} {}", service, getUDN(), succeeded ? "succeeded" : "failed");
        if (succeeded) {
            synchronized (upnpLock) {
                subscriptions.put(service, Instant.now());
            }
        }
    }

    @Override
    public void onStatusChanged(boolean status) {
        if (status) {
            logger.debug("UPnP device {} is present", getUDN());
            reconnectCount = 0;
            // Re-add subscriptions when device comes back
            synchronized (upnpLock) {
                subscriptions.forEach((serviceId, lastRenewed) -> {
                    logger.debug("Re-adding subscription for service {}", serviceId);
                    upnpIOService.addSubscription(this, serviceId, SUBSCRIPTION_DURATION);
                });
            }
        } else {
            logger.info("UPnP device {} is absent", getUDN());
            synchronized (upnpLock) {
                for (Entry<String, Instant> subscription : subscriptions.entrySet()) {
                    subscription.setValue(Instant.MIN);
                }
            }
            handleCommunicationError(new LinkPlayCommunicationException("UPnP connection lost"));
        }
    }

    private void handleAVTransportEvent(String variable, String value) {
        // Handle AVTransport events (playback status, track info, etc.)
        // To be implemented based on specific needs
    }

    private void handleRenderingControlEvent(String variable, String value) {
        // Handle RenderingControl events (volume, mute, etc.)
        // To be implemented based on specific needs
    }
}
