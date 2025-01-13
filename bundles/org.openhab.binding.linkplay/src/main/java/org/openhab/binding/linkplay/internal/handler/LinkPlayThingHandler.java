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
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.jupnp.model.types.ServiceId;
import org.jupnp.model.types.UDAServiceId;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpClient;
import org.openhab.binding.linkplay.internal.upnp.DIDLParser;
import org.openhab.binding.linkplay.internal.utils.HexConverter;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.transport.upnp.UpnpIOParticipant;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.library.types.NextPreviousType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.PlayPauseType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

/**
 * The {@link LinkPlayThingHandler} is responsible for handling commands and status
 * updates for LinkPlay devices.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayThingHandler extends BaseThingHandler implements UpnpIOParticipant {
    private final Logger logger = LoggerFactory.getLogger(LinkPlayThingHandler.class);

    private final UpnpIOService upnpIOService;
    private final LinkPlayHttpClient linkplayClient;
    private final Object upnpLock = new Object();
    private final LinkPlayGroupManager groupManager;

    private @Nullable ScheduledFuture<?> pollingJob;
    private boolean isReachable = false;

    // UPnP related fields
    private static final String UPNP_SERVICE_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String UPNP_SERVICE_RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1";
    private static final Collection<String> SERVICE_SUBSCRIPTIONS = Arrays.asList(UPNP_SERVICE_AV_TRANSPORT,
            UPNP_SERVICE_RENDERING_CONTROL);
    protected static final int SUBSCRIPTION_DURATION = 600;
    private @Nullable ScheduledFuture<?> subscriptionJob;
    private final Map<String, Instant> subscriptionTimes = new ConcurrentHashMap<>();

    private static final int MAX_REGISTRATION_RETRIES = 3;
    private static final int INITIAL_RETRY_DELAY_MS = 1000;
    private static final int MAX_RETRY_DELAY_MS = 10000;

    // Add UPnP service constants
    private static final UDAServiceId AVTRANSPORT_SERVICE_ID = new UDAServiceId("AVTransport");
    private static final UDAServiceId RENDERING_CONTROL_SERVICE_ID = new UDAServiceId("RenderingControl");

    private static final ServiceId SERVICE_ID_AV_TRANSPORT = new UDAServiceId("AVTransport");
    private static final ServiceId SERVICE_ID_RENDERING_CONTROL = new UDAServiceId("RenderingControl");

    // Add constants for UPnP services
    private static final Set<String> SUBSCRIBED_UPNP_SERVICES = Set.of(SERVICE_ID_AV_TRANSPORT.toString(),
            SERVICE_ID_RENDERING_CONTROL.toString());

    private final LinkPlayConfiguration config;

    public LinkPlayThingHandler(Thing thing, UpnpIOService upnpIOService, LinkPlayHttpClient linkplayClient) {
        super(thing);
        this.upnpIOService = upnpIOService;
        this.linkplayClient = linkplayClient;
        this.config = getConfigAs(LinkPlayConfiguration.class);
        this.groupManager = new LinkPlayGroupManager(this, linkplayClient, config.ipAddress);
    }

    @Override
    public void initialize() {
        logger.debug("[{}] Initializing LinkPlay handler", getThing().getUID());
        updateStatus(ThingStatus.UNKNOWN);

        LinkPlayConfiguration config = getConfigAs(LinkPlayConfiguration.class);
        if (config.ipAddress == null || config.ipAddress.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "IP address not configured");
            return;
        }

        // Start polling for device status
        startAutomaticRefresh();

        // Handle UPnP registration
        String udn = getUDN();
        if (!udn.isEmpty()) {
            logger.debug("[{}] Registering UPnP participant with UDN '{}' at {}", getThing().getUID(), udn,
                    java.time.LocalDateTime.now());

            // Schedule UPnP registration and verification
            scheduler.schedule(() -> {
                try {
                    registerUpnpWithRetry();

                    if (isUpnpDeviceRegistered()) {
                        logger.debug("[{}] UPnP device successfully registered, adding subscriptions",
                                getThing().getUID());
                        addSubscriptions();
                    } else {
                        logger.warn("[{}] UPnP device registration failed for UDN '{}'", getThing().getUID(), udn);
                    }
                } catch (Exception e) {
                    logger.warn("[{}] Error during UPnP initialization: {}", getThing().getUID(), e.getMessage());
                }
            }, 2, TimeUnit.SECONDS);
        } else {
            logger.warn("[{}] No UDN available, skipping UPnP registration", getThing().getUID());
        }
    }

    private void startAutomaticRefresh() {
        ScheduledFuture<?> localPollingJob = pollingJob;
        if (localPollingJob == null || localPollingJob.isCancelled()) {
            pollingJob = scheduler.scheduleWithFixedDelay(() -> {
                try {
                    if (!isReachable) {
                        logger.debug("[{}] Trying to reconnect to device", getThing().getUID());
                    }

                    // Get both player status and extended status
                    linkplayClient.getPlayerStatus(config.ipAddress).thenAccept(playerStatus -> {
                        if (!isReachable) {
                            isReachable = true;
                            updateStatus(ThingStatus.ONLINE);
                            synchronized (upnpLock) {
                                addSubscriptions();
                            }
                        }
                        updateChannelsFromStatus(playerStatus);
                    }).exceptionally(e -> {
                        if (e.getCause() instanceof Exception) {
                            handleCommunicationError((Exception) e.getCause());
                        } else {
                            handleConnectionError(e);
                        }
                        return null;
                    });

                    linkplayClient.getStatusEx(config.ipAddress).thenAccept(statusEx -> {
                        if (!isReachable) {
                            isReachable = true;
                            updateStatus(ThingStatus.ONLINE);
                            synchronized (upnpLock) {
                                addSubscriptions();
                            }
                        }
                        handleDeviceStatusUpdate(statusEx);
                        updateChannelsFromStatus(statusEx);
                    }).exceptionally(e -> {
                        if (e.getCause() instanceof Exception) {
                            handleCommunicationError((Exception) e.getCause());
                        } else {
                            handleConnectionError(e);
                        }
                        return null;
                    });

                } catch (Exception e) {
                    handleConnectionError(e);
                }
            }, 0, config.pollingInterval, TimeUnit.SECONDS);
        }
    }

    private void handleDeviceStatusUpdate(JsonObject status) {
        Map<String, String> properties = editProperties();

        // Update device name if available
        if (status.has("DeviceName")) {
            properties.put("deviceName", status.get("DeviceName").getAsString());
        }

        // Update firmware version if available
        if (status.has("firmware")) {
            properties.put(PROPERTY_FIRMWARE, status.get("firmware").getAsString());
        }

        // Handle UDN discovery only if we don't have one
        if (getUDN().isEmpty() && status.has("upnp_uuid")) {
            handleUDNUpdate(status.get("upnp_uuid").getAsString());
        }

        updateProperties(properties);
    }

    private void handleUDNUpdate(String discoveredUDN) {
        if (discoveredUDN.isEmpty()) {
            logger.debug("[{}] Ignoring empty UDN", getThing().getUID());
            return;
        }

        // Ensure proper UDN format
        String normalizedUDN = discoveredUDN.startsWith("uuid:") ? discoveredUDN : "uuid:" + discoveredUDN;

        String currentUDN = getUDN();
        if (currentUDN.isEmpty() || !currentUDN.equals(normalizedUDN)) {
            logger.debug("[{}] Discovered/Updated UDN: {}", getThing().getUID(), normalizedUDN);

            // Update properties
            Map<String, String> properties = editProperties();
            properties.put(PROPERTY_UDN, normalizedUDN);
            updateProperties(properties);

            // Update configuration
            Configuration config = editConfiguration();
            config.put(CONFIG_UDN, normalizedUDN);
            updateConfiguration(config);

            // Register for UPnP if not already registered
            if (!isUpnpDeviceRegistered()) {
                logger.debug("[{}] Registering UPnP device with UDN {}", getThing().getUID(), normalizedUDN);
                upnpIOService.registerParticipant(this);
                addSubscriptions();
            } else {
                // If already registered but UDN changed, update subscriptions
                logger.debug("[{}] UPnP device already registered, updating subscriptions", getThing().getUID());
                removeSubscriptions();
                addSubscriptions();
            }
        }
    }

    private void handleConnectionError(Throwable e) {
        isReachable = false;
        String message = e.getMessage() != null ? e.getMessage() : "Unknown error";
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, message);
        logger.debug("[{}] HTTP connection error: {}", getThing().getUID(), message);

        // Also remove UPnP subscriptions when device goes offline
        synchronized (upnpLock) {
            removeSubscriptions();
        }
    }

    @Override
    public void dispose() {
        logger.debug("[{}] Disposing handler", getThing().getUID());

        // Cancel polling job
        stopPolling();

        // Clean up UPnP
        synchronized (upnpLock) {
            removeSubscriptions();
            upnpIOService.unregisterParticipant(this);
            subscriptionTimes.clear();
        }

        super.dispose();
    }

    private void stopPolling() {
        ScheduledFuture<?> job = pollingJob;
        if (job != null && !job.isCancelled()) {
            job.cancel(true);
            pollingJob = null;
        }
    }

    @Override
    public String getUDN() {
        String configUdn = config.getUdn();
        return configUdn != null ? configUdn : "";
    }

    private boolean isUpnpDeviceRegistered() {
        String udn = getUDN();
        return !udn.isEmpty() && upnpIOService.isRegistered(this);
    }

    private boolean registerSubscription(String udn, ServiceId serviceId) {
        synchronized (upnpLock) {
            try {
                logger.debug("[{}] Registering subscription for service {}", getThing().getUID(), serviceId);
                upnpIOService.addSubscription(this, serviceId.toString(), SUBSCRIPTION_DURATION);
                return true;
            } catch (Exception e) {
                logger.debug("[{}] Failed to register subscription for service {}: {}", getThing().getUID(), serviceId,
                        e.getMessage());
                return false;
            }
        }
    }

    private void addSubscriptions() {
        synchronized (upnpLock) {
            if (!isUpnpDeviceRegistered()) {
                logger.debug("[{}] Cannot add subscriptions - device not registered", getThing().getUID());
                return;
            }

            for (String service : SUBSCRIBED_UPNP_SERVICES) {
                if (registerSubscription(getUDN(), new UDAServiceId(service))) {
                    subscriptionTimes.put(service, Instant.now());
                    logger.debug("[{}] Added subscription for service {}", getThing().getUID(), service);
                }
            }

            scheduleSubscriptionRenewal();
        }
    }

    private void removeSubscriptions() {
        synchronized (upnpLock) {
            logger.debug("[{}] Removing UPnP subscriptions", getThing().getUID());

            // Cancel any pending renewal
            ScheduledFuture<?> job = subscriptionJob;
            if (job != null && !job.isCancelled()) {
                job.cancel(true);
                subscriptionJob = null;
            }

            // Remove all subscriptions
            for (String service : SUBSCRIBED_UPNP_SERVICES) {
                try {
                    upnpIOService.removeSubscription(this, service);
                    logger.debug("[{}] Removed subscription for service {}", getThing().getUID(), service);
                } catch (Exception e) {
                    logger.debug("[{}] Error removing subscription for service {}: {}", getThing().getUID(), service,
                            e.getMessage());
                }
            }
            subscriptionTimes.clear();
        }
    }

    private void scheduleSubscriptionRenewal() {
        synchronized (upnpLock) {
            ScheduledFuture<?> job = subscriptionJob;
            if (job == null || job.isCancelled()) {
                subscriptionJob = scheduler.scheduleWithFixedDelay(() -> {
                    synchronized (upnpLock) {
                        for (String service : SUBSCRIBED_UPNP_SERVICES) {
                            Instant lastRenewal = subscriptionTimes.get(service);
                            if (lastRenewal == null
                                    || lastRenewal.plusSeconds(SUBSCRIPTION_DURATION / 2).isBefore(Instant.now())) {
                                logger.debug("[{}] Renewing subscription for service {}", getThing().getUID(), service);
                                if (registerSubscription(getUDN(), new UDAServiceId(service))) {
                                    subscriptionTimes.put(service, Instant.now());
                                }
                            }
                        }
                    }
                }, SUBSCRIPTION_DURATION / 2, SUBSCRIPTION_DURATION / 2, TimeUnit.SECONDS);
                logger.debug("[{}] Scheduled subscription renewal task", getThing().getUID());
            }
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (getThing().getStatus() != ThingStatus.ONLINE) {
            logger.debug("[{}] Thing not ONLINE, ignoring command {}", getThing().getUID(), command);
            return;
        }

        if (command instanceof RefreshType) {
            handleRefresh(channelUID);
            return;
        }

        String groupId = channelUID.getGroupId();
        if (groupId == null) {
            logger.debug("[{}] No group ID for channel {}", getThing().getUID(), channelUID);
            return;
        }

        try {
            switch (groupId) {
                case GROUP_PLAYBACK:
                    handlePlaybackCommand(channelUID, command);
                    break;
                case GROUP_MULTIROOM:
                    handleMultiroomCommand(channelUID, command);
                    break;
                default:
                    logger.debug("[{}] Unknown channel group: {}", getThing().getUID(), groupId);
            }
        } catch (Exception e) {
            handleCommandError("Error handling command", channelUID, command, e);
        }
    }

    private void handleCommandError(String message, ChannelUID channelUID, Command command, Exception e) {
        String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
        logger.warn("[{}] {} {} for channel {}: {}", getThing().getUID(), message, command, channelUID, errorMessage);
    }

    protected void handleCommunicationError(Exception e) {
        // Only update status if we're not already offline
        if (thing.getStatus() != ThingStatus.OFFLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Device not reachable: " + e.getMessage());

            // Also remove UPnP subscriptions when device goes offline
            synchronized (upnpLock) {
                removeSubscriptions();
            }
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

    private void pollDeviceState(Set<String> channels) {
        String ipAddress = config.ipAddress;
        logger.debug("[{}] Polling device state for channels: {}", thing.getUID(), channels);

        if (channels.stream().anyMatch(id -> id.startsWith(GROUP_PLAYBACK))) {
            linkplayClient.getPlayerStatus(ipAddress).thenAccept(status -> {
                logger.debug("[{}] Player status check successful", thing.getUID());
                if (thing.getStatus() != ThingStatus.ONLINE) {
                    updateStatus(ThingStatus.ONLINE);
                }
                updateChannelsFromStatus(status);
            }).exceptionally(e -> {
                if (e.getCause() instanceof Exception) {
                    handleCommunicationError((Exception) e.getCause());
                } else {
                    handleConnectionError(e);
                }
                return null;
            });

            linkplayClient.getStatusEx(ipAddress).thenAccept(statusEx -> {
                logger.debug("[{}] Extended status check successful", thing.getUID());
                if (thing.getStatus() != ThingStatus.ONLINE) {
                    updateStatus(ThingStatus.ONLINE);
                }
                updateChannelsFromStatus(statusEx);
            }).exceptionally(e -> {
                if (e.getCause() instanceof Exception) {
                    handleCommunicationError((Exception) e.getCause());
                } else {
                    handleConnectionError(e);
                }
                return null;
            });
        }

        if (channels.stream().anyMatch(id -> id.startsWith(GROUP_MULTIROOM))) {
            logger.debug("[{}] Updating group state", thing.getUID());
            groupManager.updateGroupState();
        }
    }

    private void handlePlaybackCommand(ChannelUID channelUID, Command command) {
        String channelId = channelUID.getIdWithoutGroup();
        String ipAddress = config.ipAddress;

        try {
            switch (channelId) {
                case CHANNEL_CONTROL:
                    if (command instanceof PlayPauseType) {
                        if (command == PlayPauseType.PLAY) {
                            linkplayClient.sendCommand(ipAddress, "setPlayerCmd:play");
                        } else if (command == PlayPauseType.PAUSE) {
                            linkplayClient.sendCommand(ipAddress, "setPlayerCmd:pause");
                        }
                    } else if (command instanceof NextPreviousType) {
                        if (command == NextPreviousType.NEXT) {
                            linkplayClient.sendCommand(ipAddress, "setPlayerCmd:next");
                        } else if (command == NextPreviousType.PREVIOUS) {
                            linkplayClient.sendCommand(ipAddress, "setPlayerCmd:prev");
                        }
                    }
                    break;

                case CHANNEL_VOLUME:
                    if (command instanceof PercentType) {
                        int volume = ((PercentType) command).intValue();
                        linkplayClient.sendCommand(ipAddress, String.format("setPlayerCmd:vol:%d", volume));
                    }
                    break;

                case CHANNEL_MUTE:
                    if (command instanceof OnOffType) {
                        boolean mute = command == OnOffType.ON;
                        linkplayClient.sendCommand(ipAddress, String.format("setPlayerCmd:mute:%d", mute ? 1 : 0));
                    }
                    break;

                default:
                    logger.debug("[{}] Channel {} not handled", getThing().getUID(), channelId);
            }
        } catch (Exception e) {
            handleCommandError("Error handling playback command", channelUID, command, e);
        }
    }

    private void handleMultiroomCommand(ChannelUID channelUID, Command command) {
        String channelId = channelUID.getIdWithoutGroup();

        try {
            switch (channelId) {
                case CHANNEL_JOIN:
                    if (command instanceof StringType) {
                        String masterIp = command.toString();
                        groupManager.joinGroup(masterIp);
                    }
                    break;

                case CHANNEL_LEAVE:
                    if (command instanceof OnOffType && command == OnOffType.ON) {
                        groupManager.leaveGroup();
                    }
                    break;

                case CHANNEL_UNGROUP:
                    if (command instanceof OnOffType && command == OnOffType.ON) {
                        groupManager.ungroup();
                    }
                    break;

                case CHANNEL_KICKOUT:
                    if (command instanceof StringType) {
                        String slaveIp = command.toString();
                        groupManager.kickoutSlave(slaveIp);
                    }
                    break;

                case CHANNEL_GROUP_VOLUME:
                    if (command instanceof PercentType) {
                        int volume = ((PercentType) command).intValue();
                        groupManager.setGroupVolume(volume);
                    }
                    break;

                case CHANNEL_GROUP_MUTE:
                    if (command instanceof OnOffType) {
                        groupManager.setGroupMute(command == OnOffType.ON);
                    }
                    break;

                default:
                    logger.debug("[{}] Unhandled multiroom channel: {}", getThing().getUID(), channelId);
            }
        } catch (Exception e) {
            handleCommandError("Error handling multiroom command", channelUID, command, e);
        }
    }

    protected void updatePlaybackChannels(JsonObject status) {
        // Playback Status
        if (status.has("status")) {
            String playStatus = status.get("status").getAsString();
            updateChannelState(GROUP_PLAYBACK, CHANNEL_CONTROL,
                    "play".equals(playStatus) ? PlayPauseType.PLAY : PlayPauseType.PAUSE);
        }

        // Position and Duration
        if (status.has("curpos") && status.has("totlen")) {
            try {
                int position = status.get("curpos").getAsInt() / 1000;
                int duration = status.get("totlen").getAsInt() / 1000;
                updateChannelState(GROUP_PLAYBACK, CHANNEL_POSITION, new QuantityType<>(position, Units.SECOND));
                updateChannelState(GROUP_PLAYBACK, CHANNEL_DURATION, new QuantityType<>(duration, Units.SECOND));
            } catch (NumberFormatException e) {
                logger.debug("[{}] Invalid position/duration values in status", getThing().getUID());
            }
        }

        // Media Information
        updateHexEncodedMetadata(status, "Title", CHANNEL_TITLE);
        updateHexEncodedMetadata(status, "Artist", CHANNEL_ARTIST);
        updateHexEncodedMetadata(status, "Album", CHANNEL_ALBUM);

        // Album Art
        if (status.has("AlbumArt")) {
            String albumArt = status.get("AlbumArt").getAsString();
            if (!albumArt.isEmpty()) {
                updateChannelState(GROUP_PLAYBACK, CHANNEL_ALBUM_ART, new StringType(albumArt));
            }
        }

        // Volume and Mute
        if (status.has("vol")) {
            try {
                int volume = status.get("vol").getAsInt();
                if (volume >= 0 && volume <= 100) {
                    updateChannelState(GROUP_PLAYBACK, CHANNEL_VOLUME, new PercentType(volume));
                }
            } catch (NumberFormatException e) {
                logger.warn("[{}] Invalid volume value in status", getThing().getUID());
            }
        }

        if (status.has("mute")) {
            try {
                boolean muted = status.get("mute").getAsInt() == 1;
                updateChannelState(GROUP_PLAYBACK, CHANNEL_MUTE, OnOffType.from(muted));
            } catch (NumberFormatException e) {
                logger.warn("[{}] Invalid mute value in status", getThing().getUID());
            }
        }

        // Playback Modes
        if (status.has("loop")) {
            try {
                int loopMode = status.get("loop").getAsInt();
                boolean shuffle = (loopMode == 2 || loopMode == 3);
                boolean repeat = (loopMode == 0 || loopMode == 2);
                updateChannelState(GROUP_PLAYBACK, CHANNEL_SHUFFLE, OnOffType.from(shuffle));
                updateChannelState(GROUP_PLAYBACK, CHANNEL_REPEAT, OnOffType.from(repeat));
            } catch (NumberFormatException e) {
                logger.debug("[{}] Invalid loop mode value in status", getThing().getUID());
            }
        }

        // Source
        if (status.has("mode")) {
            String mode = status.get("mode").getAsString();
            updateChannelState(GROUP_PLAYBACK, CHANNEL_SOURCE, new StringType(mapModeToSource(mode)));
        }
    }

    private void updateDeviceChannels(JsonObject status) {
        // Device Name
        if (status.has("DeviceName")) {
            updateChannelState(GROUP_SYSTEM, CHANNEL_DEVICE_NAME,
                    new StringType(status.get("DeviceName").getAsString()));
        }

        // Firmware Version
        if (status.has("firmware")) {
            updateChannelState(GROUP_SYSTEM, CHANNEL_FIRMWARE, new StringType(status.get("firmware").getAsString()));
        }
    }

    private void updateNetworkChannels(JsonObject status) {
        // IP Address
        if (status.has("ip")) {
            updateChannelState(GROUP_NETWORK, CHANNEL_IP_ADDRESS, new StringType(status.get("ip").getAsString()));
        }

        // MAC Address
        if (status.has("mac")) {
            updateChannelState(GROUP_NETWORK, CHANNEL_MAC_ADDRESS, new StringType(status.get("mac").getAsString()));
        }
    }

    private void updateMultiroomChannels(JsonObject status) {
        // Let the group manager handle multiroom status updates
        if (status.has("uuid") || status.has("host_uuid") || status.has("slave_list")) {
            groupManager.handleStatusUpdate(status);
        }
    }

    @Override
    public void onValueReceived(@Nullable String variable, @Nullable String value, @Nullable String service) {
        if (variable == null || value == null || service == null) {
            logger.debug("[{}] Received incomplete UPnP value update", getThing().getUID());
            return;
        }

        if (getThing().getStatus() != ThingStatus.ONLINE) {
            return;
        }

        logger.trace("[{}] Received UPnP value '{}' = '{}' for service {}", getThing().getUID(), variable, value,
                service);

        try {
            if (service.contains(SERVICE_ID_AV_TRANSPORT.toString())) {
                handleAVTransportUpdate(variable, value);
            } else if (service.contains(SERVICE_ID_RENDERING_CONTROL.toString())) {
                handleRenderingControlUpdate(variable, value);
            }
        } catch (Exception e) {
            logger.debug("[{}] Error processing UPnP value: {}", getThing().getUID(), e.getMessage());
        }
    }

    private void handleAVTransportUpdate(String variable, String value) {
        try {
            switch (variable) {
                case "TransportState":
                    updateChannelState(GROUP_PLAYBACK, CHANNEL_CONTROL,
                            "PLAYING".equals(value) ? PlayPauseType.PLAY : PlayPauseType.PAUSE);
                    break;
                case "CurrentTrackMetaData":
                    if (!value.isEmpty()) {
                        updateTrackMetadata(value);
                    }
                    break;
                case "CurrentTrackDuration":
                    if (!value.isEmpty() && !"NOT_IMPLEMENTED".equals(value)) {
                        updateDuration(value);
                    }
                    break;
                default:
                    logger.trace("[{}] Unhandled AVTransport variable: {}", getThing().getUID(), variable);
                    break;
            }
        } catch (Exception e) {
            logger.debug("[{}] Error handling AVTransport update for {}: {}", getThing().getUID(), variable,
                    e.getMessage());
        }
    }

    private void updateDuration(String durationStr) {
        try {
            String[] parts = durationStr.split(":");
            if (parts.length == 3) {
                int hours = Integer.parseInt(parts[0]);
                int minutes = Integer.parseInt(parts[1]);
                int seconds = Integer.parseInt(parts[2]);
                int totalSeconds = (hours * 3600) + (minutes * 60) + seconds;
                updateChannelState(GROUP_PLAYBACK, CHANNEL_DURATION, new QuantityType<>(totalSeconds, Units.SECOND));
            }
        } catch (NumberFormatException e) {
            logger.debug("[{}] Error parsing duration '{}': {}", getThing().getUID(), durationStr, e.getMessage());
        }
    }

    private void handleRenderingControlUpdate(String variable, String value) {
        try {
            switch (variable) {
                case "Volume":
                    if (!value.isEmpty()) {
                        updateChannelState(GROUP_PLAYBACK, CHANNEL_VOLUME, new PercentType(value));
                    }
                    break;
                case "Mute":
                    if (!value.isEmpty()) {
                        updateChannelState(GROUP_PLAYBACK, CHANNEL_MUTE, OnOffType.from("1".equals(value)));
                    }
                    break;
                default:
                    logger.trace("[{}] Unhandled RenderingControl variable: {}", getThing().getUID(), variable);
                    break;
            }
        } catch (Exception e) {
            logger.debug("[{}] Error handling RenderingControl update for {}: {}", getThing().getUID(), variable,
                    e.getMessage());
        }
    }

    private void updateTrackMetadata(String metadata) {
        try {
            DIDLParser.MetaData content = DIDLParser.parseMetadata(metadata);
            if (content != null) {
                if (content.title != null && !content.title.isEmpty()) {
                    updateChannelState(GROUP_PLAYBACK, CHANNEL_TITLE, new StringType(content.title));
                }

                if (content.artist != null && !content.artist.isEmpty()) {
                    updateChannelState(GROUP_PLAYBACK, CHANNEL_ARTIST, new StringType(content.artist));
                }

                if (content.album != null && !content.album.isEmpty()) {
                    updateChannelState(GROUP_PLAYBACK, CHANNEL_ALBUM, new StringType(content.album));
                }

                if (content.artworkUrl != null && !content.artworkUrl.isEmpty()) {
                    updateChannelState(GROUP_PLAYBACK, CHANNEL_ALBUM_ART, new StringType(content.artworkUrl));
                }

                logger.debug("[{}] Updated track metadata - Title: {}, Artist: {}, Album: {}", getThing().getUID(),
                        content.title, content.artist, content.album);
            }
        } catch (Exception e) {
            logger.debug("[{}] Error parsing track metadata: {}", getThing().getUID(), e.getMessage());
        }
    }

    private void updateHexEncodedMetadata(JsonObject status, String field, String channelId) {
        if (status.has(field)) {
            String hexValue = status.get(field).getAsString();
            try {
                String decoded = HexConverter.hexToString(hexValue);
                if (!decoded.isEmpty()) {
                    updateChannelState(GROUP_PLAYBACK, channelId, new StringType(decoded));
                }
            } catch (Exception e) {
                logger.debug("[{}] Error decoding {} metadata: {}", getThing().getUID(), field, e.getMessage());
            }
        }
    }

    @Override
    public void onServiceSubscribed(@Nullable String serviceId, boolean succeeded) {
        synchronized (upnpLock) {
            if (serviceId != null && succeeded) {
                subscriptionTimes.put(serviceId, Instant.now());
                logger.debug("[{}] Renewed subscription for service {}", getThing().getUID(), serviceId);
            } else {
                logger.debug("[{}] Failed to renew subscription for service {}", getThing().getUID(), serviceId);
                if (serviceId != null) {
                    subscriptionTimes.remove(serviceId);
                }
            }
        }
    }

    @Override
    public void onStatusChanged(boolean status) {
        synchronized (upnpLock) {
            logger.debug("[{}] UPnP status changed to {}", getThing().getUID(), status);

            if (status) {
                addSubscriptions();
            } else {
                removeSubscriptions();
            }
        }
    }

    protected void updateChannelsFromStatus(JsonObject status) {
        logger.debug("[{}] Updating channels from status", getThing().getUID());

        try {
            // Playback channels
            updatePlaybackChannels(status);

            // Device channels
            updateDeviceChannels(status);

            // Network channels
            updateNetworkChannels(status);

            // Multiroom channels
            updateMultiroomChannels(status);

        } catch (Exception e) {
            logger.warn("[{}] Error updating channels from status: {}", getThing().getUID(), e.getMessage());
        }
    }

    private String mapModeToSource(String mode) {
        switch (mode.toLowerCase()) {
            case "airplay":
                return "Airplay";
            case "dlna":
                return "DLNA";
            case "line-in":
                return "Line-In";
            case "bluetooth":
                return "Bluetooth";
            case "optical":
                return "Optical";
            case "usb":
                return "USB";
            case "udisk":
                return "USB";
            case "spotify":
                return "Spotify";
            case "idle":
                return "Idle";
            default:
                return mode;
        }
    }

    @Override
    public void handleRemoval() {
        // Clean up everything when thing is removed
        stopPolling();
        synchronized (upnpLock) {
            removeSubscriptions();
            upnpIOService.unregisterParticipant(this);
            subscriptionTimes.clear();
        }
        super.handleRemoval();
    }

    @Override
    public void thingUpdated(Thing thing) {
        // Check if thing was disabled
        if (!thing.isEnabled() && getThing().isEnabled()) {
            // Thing was disabled, stop everything
            logger.debug("[{}] Thing disabled, stopping all operations", getThing().getUID());
            stopPolling();
            synchronized (upnpLock) {
                removeSubscriptions();
                upnpIOService.unregisterParticipant(this);
                subscriptionTimes.clear();
            }
        }
        super.thingUpdated(thing);
    }

    private void registerUpnpService() {
        synchronized (upnpLock) {
            String udn = getConfigAs(LinkPlayConfiguration.class).getUdn();
            if (udn == null || upnpIOService == null) {
                return;
            }

            logger.debug("Registering UPnP services for device {}", udn);

            // Register for AVTransport events
            if (registerSubscription(udn, AVTRANSPORT_SERVICE_ID)) {
                subscriptionTimes.put(AVTRANSPORT_SERVICE_ID.toString(), Instant.now());
            }

            // Register for RenderingControl events
            if (registerSubscription(udn, RENDERING_CONTROL_SERVICE_ID)) {
                subscriptionTimes.put(RENDERING_CONTROL_SERVICE_ID.toString(), Instant.now());
            }
        }
    }

    private void updateChannelState(String groupId, String channelId, State state) {
        ChannelUID channelUID = new ChannelUID(getThing().getUID(), groupId, channelId);
        updateState(channelUID, state);
    }

    private void registerUpnpWithRetry() {
        synchronized (upnpLock) {
            if (!isUpnpDeviceRegistered()) {
                logger.debug("[{}] Registering UPnP device", getThing().getUID());
                upnpIOService.registerParticipant(this);
                addSubscriptions();
            }
        }
    }

    protected void updateGroupChannels(String role, String masterIP, String slaveIPs) {
        updateChannelState(GROUP_MULTIROOM, CHANNEL_ROLE, new StringType(role));
        if (masterIP != null && !masterIP.isEmpty()) {
            updateChannelState(GROUP_MULTIROOM, CHANNEL_MASTER_IP, new StringType(masterIP));
        }
        if (slaveIPs != null && !slaveIPs.isEmpty()) {
            updateChannelState(GROUP_MULTIROOM, CHANNEL_SLAVE_IPS, new StringType(slaveIPs));
        }
    }

    private void initializeGroupManager() {
        groupManager = new LinkPlayGroupManager(this, linkplayClient, config.ipAddress);
    }

    @Override
    public void onNotificationReceived(String udn, String svcId, String data) {
        if (udn == null || svcId == null || data == null) {
            logger.debug("[{}] Received incomplete UPnP notification", getThing().getUID());
            return;
        }

        logger.trace("[{}] Received UPnP notification for service {}", getThing().getUID(), svcId);

        try {
            if (svcId.contains(SERVICE_ID_AV_TRANSPORT.toString())) {
                Map<String, String> eventValues = DIDLParser.getAVTransportFromXML(data);
                for (Map.Entry<String, String> entry : eventValues.entrySet()) {
                    handleAVTransportUpdate(entry.getKey(), entry.getValue());
                }
            } else if (svcId.contains(SERVICE_ID_RENDERING_CONTROL.toString())) {
                Map<String, String> eventValues = DIDLParser.getRenderingControlFromXML(data);
                for (Map.Entry<String, String> entry : eventValues.entrySet()) {
                    handleRenderingControlUpdate(entry.getKey(), entry.getValue());
                }
            }
        } catch (Exception e) {
            logger.debug("[{}] Error processing UPnP notification: {}", getThing().getUID(), e.getMessage());
        }
    }

    @Override
    public void onSubscription(String service, String publication) {
        logger.trace("[{}] Subscription callback received for service {}", getThing().getUID(), service);
    }

    private void initializeUPnP() {
        String udn = getUDN();
        if (!udn.isEmpty()) {
            // Schedule UPnP registration and verification
            scheduler.schedule(() -> {
                try {
                    registerUpnpWithRetry();

                    if (isUpnpDeviceRegistered()) {
                        logger.debug("[{}] UPnP device successfully registered", getThing().getUID());
                        addSubscriptions();
                    } else {
                        logger.warn("[{}] UPnP device registration failed", getThing().getUID());
                    }
                } catch (Exception e) {
                    logger.warn("[{}] Error during UPnP initialization: {}", getThing().getUID(), e.getMessage());
                }
            }, 2, TimeUnit.SECONDS);
        } else {
            logger.warn("[{}] No UDN available, skipping UPnP registration", getThing().getUID());
        }
    }
}
