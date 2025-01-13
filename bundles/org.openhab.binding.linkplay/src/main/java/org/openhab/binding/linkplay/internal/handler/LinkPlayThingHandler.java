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
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.binding.linkplay.internal.http.LinkPlayApiException;
import org.openhab.binding.linkplay.internal.http.LinkPlayCommunicationException;
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

    public LinkPlayThingHandler(Thing thing, UpnpIOService upnpIOService, LinkPlayHttpClient linkplayClient) {
        super(thing);
        this.upnpIOService = upnpIOService;
        this.linkplayClient = linkplayClient;
        this.groupManager = new LinkPlayGroupManager(thing, linkplayClient);
    }

    @Override
    public void initialize() {
        logger.debug("[{}] Initializing LinkPlay handler", getThing().getUID());
        updateStatus(ThingStatus.UNKNOWN);

        // Start polling for device status
        startAutomaticRefresh();

        // Handle UPnP registration separately
        String udn = getUDN();
        if (!udn.isEmpty()) {
            logger.debug("[{}] Registering UPnP participant with UDN '{}' at {}", getThing().getUID(), udn,
                    java.time.LocalDateTime.now());
            upnpIOService.registerParticipant(this);

            // Schedule UPnP verification and subscription after a delay
            scheduler.schedule(() -> {
                synchronized (upnpLock) {
                    logger.debug("[{}] Checking UPnP registration status after delay", getThing().getUID());
                    if (isUpnpDeviceRegistered()) {
                        logger.debug("[{}] UPnP device successfully registered, adding subscriptions",
                                getThing().getUID());
                        addSubscriptions();
                    } else {
                        logger.warn("[{}] UPnP device registration failed for UDN '{}'", getThing().getUID(), udn);
                    }
                }
            }, 2, TimeUnit.SECONDS);
        } else {
            logger.warn("[{}] No UDN available, skipping UPnP registration", getThing().getUID());
        }
    }

    private void startAutomaticRefresh() {
        ScheduledFuture<?> localPollingJob = pollingJob;
        if (localPollingJob == null || localPollingJob.isCancelled()) {
            LinkPlayConfiguration config = getConfigAs(LinkPlayConfiguration.class);
            pollingJob = scheduler.scheduleWithFixedDelay(() -> {
                try {
                    if (!isReachable) {
                        // Device was offline, try to reconnect
                        logger.debug("[{}] Trying to reconnect to device", getThing().getUID());
                    }

                    // Get both player status and extended status
                    linkplayClient.getPlayerStatus(config.ipAddress).thenAccept(playerStatus -> {
                        if (!isReachable) {
                            isReachable = true;
                            updateStatus(ThingStatus.ONLINE);
                            // Add UPnP subscriptions when device comes online
                            synchronized (upnpLock) {
                                addSubscriptions();
                            }
                        }
                        // Update channels with player status
                        updateChannelsFromStatus(playerStatus);
                    }).exceptionally(e -> {
                        handleConnectionError(e);
                        return null;
                    });

                    linkplayClient.getStatusEx(config.ipAddress).thenAccept(statusEx -> {
                        if (!isReachable) {
                            isReachable = true;
                            updateStatus(ThingStatus.ONLINE);
                            // Add UPnP subscriptions when device comes online
                            synchronized (upnpLock) {
                                addSubscriptions();
                            }
                        }
                        // Update properties and handle UDN discovery
                        handleDeviceStatusUpdate(statusEx);
                        // Update channels with extended status
                        updateChannelsFromStatus(statusEx);
                    }).exceptionally(e -> {
                        handleConnectionError(e);
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
            logger.debug("[{}] Unregistering UPnP participant", getThing().getUID());
            upnpIOService.unregisterParticipant(this);
            subscriptionTimes.clear();
        }

        super.dispose();
    }

    private void stopPolling() {
        logger.debug("[{}] Stopping polling", getThing().getUID());
        ScheduledFuture<?> job = pollingJob;
        if (job != null && !job.isCancelled()) {
            job.cancel(true);
            pollingJob = null;
        }
    }

    @Override
    public String getUDN() {
        String configUdn = getConfigAs(LinkPlayConfiguration.class).udn;
        if (!configUdn.isEmpty()) {
            return configUdn;
        }
        String propertyUdn = getThing().getProperties().get(PROPERTY_UDN);
        return propertyUdn != null ? propertyUdn : "";
    }

    protected boolean isUpnpDeviceRegistered() {
        return upnpIOService.isRegistered(this);
    }

    private void addSubscriptions() {
        synchronized (upnpLock) {
            String udn = getUDN();
            if (!isUpnpDeviceRegistered()) {
                logger.warn("[{}] Cannot add UPnP subscriptions - device not registered (UDN: {})", getThing().getUID(),
                        udn);
                return;
            }
            logger.debug("[{}] Adding UPnP subscriptions for device {} at {}", getThing().getUID(), udn,
                    java.time.LocalDateTime.now());

            for (String service : SERVICE_SUBSCRIPTIONS) {
                logger.debug("[{}] Adding UPnP subscription for service {} (current subscriptions: {})",
                        getThing().getUID(), service, subscriptionTimes.keySet());
                upnpIOService.addSubscription(this, service, SUBSCRIPTION_DURATION);
            }
        }
    }

    private void removeSubscriptions() {
        synchronized (upnpLock) {
            String udn = getUDN();
            logger.debug("[{}] Removing UPnP subscriptions for device {} at {} (current subscriptions: {})",
                    getThing().getUID(), udn, java.time.LocalDateTime.now(), subscriptionTimes.keySet());

            for (String service : SERVICE_SUBSCRIPTIONS) {
                logger.debug("[{}] Removing UPnP subscription for service {}", getThing().getUID(), service);
                upnpIOService.removeSubscription(this, service);
            }
            subscriptionTimes.clear();
            logger.debug("[{}] All UPnP subscriptions removed", getThing().getUID());
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
        String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
        if (e instanceof LinkPlayCommunicationException) {
            logger.warn("{} {} for channel {}: {}", message, command, channelUID, errorMessage);
            handleCommunicationError(e);
        } else if (e instanceof LinkPlayApiException) {
            logger.debug("{} {} for channel {}: {}", message, command, channelUID, errorMessage);
        } else {
            logger.warn("{} {} for channel {}: {}", message, command, channelUID, errorMessage);
        }
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
        String ipAddress = getConfigAs(LinkPlayConfiguration.class).ipAddress;
        logger.debug("[{}] Polling device state for channels: {}", thing.getUID(), channels);

        if (channels.stream().anyMatch(id -> id.startsWith(GROUP_PLAYBACK))) {
            // Get both player status and extended status
            linkplayClient.getPlayerStatus(ipAddress).thenAccept(status -> {
                logger.debug("[{}] Player status check successful", thing.getUID());
                if (thing.getStatus() != ThingStatus.ONLINE) {
                    updateStatus(ThingStatus.ONLINE);
                }
                updateChannelsFromStatus(status);
            }).exceptionally(e -> {
                if (e != null) {
                    logger.debug("[{}] Error polling player status: {}", thing.getUID(),
                            e.getMessage() != null ? e.getMessage() : "Unknown error");
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Device not responding: " + (e.getMessage() != null ? e.getMessage() : "Unknown error"));
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
                if (e != null) {
                    logger.debug("[{}] Error polling extended status: {}", thing.getUID(),
                            e.getMessage() != null ? e.getMessage() : "Unknown error");
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Device not responding: " + (e.getMessage() != null ? e.getMessage() : "Unknown error"));
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
        String ipAddress = getConfigAs(LinkPlayConfiguration.class).ipAddress;
        String channelId = channelUID.getIdWithoutGroup();

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
                    logger.debug("Channel {} not handled", channelId);
            }
        } catch (Exception e) {
            handleCommandError("playback command", channelUID, command, e);
        }
    }

    protected void updatePlaybackChannels(JsonObject status) {
        if (status.has("status")) {
            String playStatus = status.get("status").getAsString();
            updateState(CHANNEL_CONTROL, "play".equals(playStatus) ? PlayPauseType.PLAY : PlayPauseType.PAUSE);
        }

        if (status.has("vol")) {
            updateState(CHANNEL_VOLUME, new PercentType(status.get("vol").getAsInt()));
        }

        if (status.has("mute")) {
            updateState(CHANNEL_MUTE, OnOffType.from(status.get("mute").getAsInt() == 1));
        }

        if (status.has("curpos") && status.has("totlen")) {
            int position = status.get("curpos").getAsInt() / 1000; // ms to seconds
            int duration = status.get("totlen").getAsInt() / 1000; // ms to seconds
            updateState(CHANNEL_POSITION, new QuantityType<>(position, Units.SECOND));
            updateState(CHANNEL_DURATION, new QuantityType<>(duration, Units.SECOND));
        }
    }

    protected void updateGroupChannels(String role, String masterIP, String slaveIPs) {
        updateState(new ChannelUID(getThing().getUID(), GROUP_MULTIROOM, CHANNEL_ROLE), new StringType(role));
        updateState(new ChannelUID(getThing().getUID(), GROUP_MULTIROOM, CHANNEL_MASTER_IP), new StringType(masterIP));
        updateState(new ChannelUID(getThing().getUID(), GROUP_MULTIROOM, CHANNEL_SLAVE_IPS), new StringType(slaveIPs));
    }

    @Override
    public void onValueReceived(@Nullable String variable, @Nullable String value, @Nullable String service) {
        if (variable == null || value == null || service == null) {
            logger.warn("[{}] Received UPnP event with null values - variable: {}, value: {}, service: {}",
                    getThing().getUID(), variable, value, service);
            return;
        }

        logger.debug(
                "[{}] Processing UPnP event at {} - Service: '{}', Variable: '{}', Value: '{}' (subscriptions: {})",
                getThing().getUID(), java.time.LocalDateTime.now(), service, variable, value,
                subscriptionTimes.keySet());

        try {
            switch (service) {
                case UPNP_SERVICE_AV_TRANSPORT:
                    handleAVTransportUpdate(variable, value);
                    break;
                case UPNP_SERVICE_RENDERING_CONTROL:
                    handleRenderingControlUpdate(variable, value);
                    break;
                default:
                    logger.debug("[{}] Unhandled UPnP service: {} (known services: {})", getThing().getUID(), service,
                            SERVICE_SUBSCRIPTIONS);
            }
        } catch (Exception e) {
            logger.warn("[{}] Error processing UPnP event: {} (service: {}, variable: {}, value: {})",
                    getThing().getUID(), e.getMessage(), service, variable, value);
        }
    }

    private void handleAVTransportUpdate(String variable, String value) {
        switch (variable) {
            case "TransportState":
                updateState(GROUP_PLAYBACK + "#" + CHANNEL_CONTROL,
                        "PLAYING".equals(value) ? PlayPauseType.PLAY : PlayPauseType.PAUSE);
                break;
            case "CurrentTrackMetaData":
                // Parse and update track metadata
                updateTrackMetadata(value);
                break;
            default:
                logger.debug("[{}] Unhandled AVTransport variable: {}", getThing().getUID(), variable);
        }
    }

    private void handleRenderingControlUpdate(String variable, String value) {
        switch (variable) {
            case "Volume":
                try {
                    int volume = Integer.parseInt(value);
                    logger.debug("[{}] Processing UPnP volume update - raw value: {}", getThing().getUID(), volume);
                    if (volume >= 0 && volume <= 100) {
                        String channelId = GROUP_PLAYBACK + "#" + CHANNEL_VOLUME;
                        logger.debug("[{}] Updating volume channel {} to {}%", getThing().getUID(), channelId, volume);
                        updateState(channelId, new PercentType(volume));
                    }
                } catch (NumberFormatException e) {
                    logger.warn("[{}] Invalid UPnP volume value: {}", getThing().getUID(), value);
                }
                break;
            case "Mute":
                updateState(GROUP_PLAYBACK + "#" + CHANNEL_MUTE, OnOffType.from("1".equals(value)));
                break;
            default:
                logger.debug("[{}] Unhandled RenderingControl variable: {}", getThing().getUID(), variable);
        }
    }

    private void updateTrackMetadata(String metadata) {
        try {
            DIDLParser.MetaData parsedMetadata = DIDLParser.parseMetadata(metadata);
            logger.debug("[{}] Parsed track metadata: {}", getThing().getUID(), parsedMetadata);

            if (!parsedMetadata.title.isEmpty()) {
                updateState(CHANNEL_TITLE, new StringType(parsedMetadata.title));
            }
            if (!parsedMetadata.artist.isEmpty()) {
                updateState(CHANNEL_ARTIST, new StringType(parsedMetadata.artist));
            }
            if (!parsedMetadata.album.isEmpty()) {
                updateState(CHANNEL_ALBUM, new StringType(parsedMetadata.album));
            }
            if (!parsedMetadata.artworkUrl.isEmpty()) {
                updateState(CHANNEL_ALBUM_ART, new StringType(parsedMetadata.artworkUrl));
            }
        } catch (Exception e) {
            logger.warn("Error updating track metadata: {}", e.getMessage());
        }
    }

    @Override
    public void onServiceSubscribed(@Nullable String service, boolean succeeded) {
        if (service == null) {
            logger.warn("[{}] Received null service in onServiceSubscribed", getThing().getUID());
            return;
        }
        synchronized (upnpLock) {
            if (succeeded) {
                Instant now = Instant.now();
                logger.debug("[{}] UPnP subscription succeeded for service {} at {} (previous subscriptions: {})",
                        getThing().getUID(), service, now, subscriptionTimes.keySet());
                subscriptionTimes.put(service, now);
                logger.debug("[{}] Updated subscription times: {}", getThing().getUID(), subscriptionTimes);
            } else {
                logger.warn("[{}] UPnP subscription failed for service {} at {} (current subscriptions: {})",
                        getThing().getUID(), service, java.time.LocalDateTime.now(), subscriptionTimes.keySet());
            }
        }
    }

    @Override
    public void onStatusChanged(boolean status) {
        String udn = getUDN();
        logger.debug("[{}] UPnP device status changed to {} for device {} at {}", getThing().getUID(), status, udn,
                java.time.LocalDateTime.now());

        if (status) {
            logger.debug("[{}] UPnP device {} is present (current subscriptions: {})", getThing().getUID(), udn,
                    subscriptionTimes.keySet());
            addSubscriptions();
        } else {
            logger.info("[{}] UPnP device {} is absent, removing subscriptions", getThing().getUID(), udn);
            synchronized (upnpLock) {
                removeSubscriptions();
            }
            // Don't change device status - let HTTP connection determine that
            logger.debug("[{}] UPnP connection lost but keeping device status as is", getThing().getUID());
        }
    }

    protected void updateChannelsFromStatus(JsonObject status) {
        logger.debug("[{}] Updating channels from status: {}", getThing().getUID(), status);

        try {
            // Get device IP first for group state
            String deviceIp = null;
            if (status.has("eth0")) {
                String ip = status.get("eth0").getAsString();
                if (!ip.isEmpty() && !"0.0.0.0".equals(ip)) {
                    deviceIp = ip;
                    updateState(GROUP_NETWORK + "#" + CHANNEL_IP_ADDRESS, new StringType(ip));
                }
            }

            // 1. Playback Control & Position
            if (status.has("status")) {
                String playStatus = status.get("status").getAsString();
                updateState(GROUP_PLAYBACK + "#" + CHANNEL_CONTROL,
                        "play".equals(playStatus) ? PlayPauseType.PLAY : PlayPauseType.PAUSE);
            }

            if (status.has("curpos") && status.has("totlen")) {
                try {
                    int position = status.get("curpos").getAsInt() / 1000; // ms to seconds
                    int duration = status.get("totlen").getAsInt() / 1000; // ms to seconds
                    updateState(GROUP_PLAYBACK + "#" + CHANNEL_POSITION, new QuantityType<>(position, Units.SECOND));
                    updateState(GROUP_PLAYBACK + "#" + CHANNEL_DURATION, new QuantityType<>(duration, Units.SECOND));
                } catch (NumberFormatException e) {
                    logger.debug("[{}] Invalid position/duration values in status", getThing().getUID());
                }
            }

            // 2. Media Information (hex encoded)
            if (status.has("Title")) {
                String title = HexConverter.hexToString(status.get("Title").getAsString());
                if (!title.isEmpty()) {
                    updateState(GROUP_PLAYBACK + "#" + CHANNEL_TITLE, new StringType(title));
                }
            }
            if (status.has("Artist")) {
                String artist = HexConverter.hexToString(status.get("Artist").getAsString());
                if (!artist.isEmpty()) {
                    updateState(GROUP_PLAYBACK + "#" + CHANNEL_ARTIST, new StringType(artist));
                }
            }
            if (status.has("Album")) {
                String album = HexConverter.hexToString(status.get("Album").getAsString());
                if (!album.isEmpty()) {
                    updateState(GROUP_PLAYBACK + "#" + CHANNEL_ALBUM, new StringType(album));
                }
            }
            if (status.has("AlbumArt")) {
                String albumArt = status.get("AlbumArt").getAsString();
                if (!albumArt.isEmpty()) {
                    updateState(GROUP_PLAYBACK + "#" + CHANNEL_ALBUM_ART, new StringType(albumArt));
                }
            }

            // 3. Volume Control
            if (status.has("vol")) {
                try {
                    int volume = status.get("vol").getAsInt();
                    logger.debug("[{}] Processing volume update - raw value: {}", getThing().getUID(), volume);
                    if (volume >= 0 && volume <= 100) {
                        String channelId = GROUP_PLAYBACK + "#" + CHANNEL_VOLUME;
                        logger.debug("[{}] Updating volume channel {} to {}%", getThing().getUID(), channelId, volume);
                        updateState(channelId, new PercentType(volume));
                    } else {
                        logger.warn("[{}] Volume value {} out of range (0-100)", getThing().getUID(), volume);
                    }
                } catch (NumberFormatException e) {
                    logger.warn("[{}] Invalid volume value in status: {}", getThing().getUID(), status.get("vol"));
                }
            } else {
                logger.debug("[{}] No volume information in status update", getThing().getUID());
            }
            if (status.has("mute")) {
                try {
                    boolean muted = status.get("mute").getAsInt() == 1;
                    String channelId = GROUP_PLAYBACK + "#" + CHANNEL_MUTE;
                    logger.debug("[{}] Updating mute channel {} to {}", getThing().getUID(), channelId, muted);
                    updateState(channelId, OnOffType.from(muted));
                } catch (NumberFormatException e) {
                    logger.warn("[{}] Invalid mute value in status: {}", getThing().getUID(), status.get("mute"));
                }
            } else {
                logger.debug("[{}] No mute information in status update", getThing().getUID());
            }

            // 4. Playback Modes
            if (status.has("loop")) {
                try {
                    int loopMode = status.get("loop").getAsInt();
                    boolean shuffle = (loopMode == 2 || loopMode == 3);
                    boolean repeat = (loopMode == 0 || loopMode == 2);
                    updateState(GROUP_PLAYBACK + "#" + CHANNEL_SHUFFLE, OnOffType.from(shuffle));
                    updateState(GROUP_PLAYBACK + "#" + CHANNEL_REPEAT, OnOffType.from(repeat));
                } catch (NumberFormatException e) {
                    logger.debug("[{}] Invalid loop mode value in status", getThing().getUID());
                }
            }

            // 5. Source
            if (status.has("mode")) {
                String mode = status.get("mode").getAsString();
                updateState(GROUP_PLAYBACK + "#" + CHANNEL_SOURCE, new StringType(mapModeToSource(mode)));
            }

            // 6. Network Information
            if (status.has("RSSI")) {
                try {
                    int rssi = status.get("RSSI").getAsInt();
                    updateState(GROUP_NETWORK + "#" + CHANNEL_WIFI_SIGNAL,
                            new QuantityType<>(rssi, Units.DECIBEL_MILLIWATTS));
                } catch (NumberFormatException e) {
                    logger.debug("[{}] Invalid RSSI value", getThing().getUID());
                }
            }
            if (status.has("MAC")) {
                String mac = status.get("MAC").getAsString();
                if (!mac.isEmpty()) {
                    updateState(GROUP_NETWORK + "#" + CHANNEL_MAC_ADDRESS, new StringType(mac));
                }
            }

            // 7. Device Information
            if (status.has("DeviceName")) {
                String deviceName = status.get("DeviceName").getAsString();
                if (!deviceName.isEmpty()) {
                    updateState(GROUP_SYSTEM + "#" + CHANNEL_DEVICE_NAME, new StringType(deviceName));
                }
            }
            if (status.has("firmware")) {
                String firmware = status.get("firmware").getAsString();
                if (!firmware.isEmpty()) {
                    updateState(GROUP_SYSTEM + "#" + CHANNEL_FIRMWARE, new StringType(firmware));
                }
            }

            // 8. Multiroom Status
            if (deviceIp != null && status.has("group")) {
                String role = "standalone";
                String masterIP = "";
                String slaveIPs = "";

                if ("1".equals(status.get("group").getAsString())) {
                    // This is part of a group
                    if (status.has("GroupRole")) {
                        role = status.get("GroupRole").getAsString().toLowerCase();
                    }
                    if (status.has("GroupMasterIP")) {
                        masterIP = status.get("GroupMasterIP").getAsString();
                    }
                    if (status.has("GroupSlaveIPs")) {
                        slaveIPs = status.get("GroupSlaveIPs").getAsString();
                    }
                }

                updateState(GROUP_MULTIROOM + "#" + CHANNEL_ROLE, new StringType(role));
                updateState(GROUP_MULTIROOM + "#" + CHANNEL_MASTER_IP, new StringType(masterIP));
                updateState(GROUP_MULTIROOM + "#" + CHANNEL_SLAVE_IPS, new StringType(slaveIPs));
            }

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
}
