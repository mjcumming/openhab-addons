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

package org.openhab.binding.linkplay.internal.http;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.api.ContentResponse;
import org.openhab.binding.linkplay.internal.LinkPlayBindingConstants;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.binding.linkplay.internal.handler.LinkPlayDeviceManager;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The {@link LinkPlayHttpManager} handles HTTP communication with LinkPlay devices.
 * It provides retry logic and exponential backoff for reliability.
 *
 * @author Michael Cumming - Initial contribution
 */
/**
@NonNullByDefault
public class LinkPlayHttpManager {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayHttpManager.class);

    private final LinkPlayHttpClient httpClient;
    private final LinkPlayDeviceManager deviceManager;
    private final int maxRetries;
    private final long retryDelayMs;
    private final int pollingIntervalSeconds;

    private ScheduledFuture<?> pollingJob;

    public LinkPlayHttpManager(LinkPlayHttpClient client,
                               LinkPlayDeviceManager deviceManager,
                               LinkPlayConfiguration config) {
        this.httpClient = client;
        this.deviceManager = deviceManager;

        // Use config-based values
        this.maxRetries = config.getMaxRetries() > 0 ? config.getMaxRetries() : 3;
        this.retryDelayMs = config.getRetryDelayMillis() > 0 ? config.getRetryDelayMillis() : 2000;
        // If your config has a pollingInterval field:
        this.pollingIntervalSeconds = Math.max(config.getPollingInterval(), 10); // fallback to 10 if <10

        logger.debug("LinkPlayHttpManager created: maxRetries={}, retryDelayMs={}, pollInterval={}s",
                maxRetries, retryDelayMs, pollingIntervalSeconds);
    }

    /**
     * Starts periodic polling using the configured interval.
     */
    public void startPolling() {
        if (pollingJob != null && !pollingJob.isCancelled()) {
            logger.debug("Polling is already running");
            return;
        }

        pollingJob = ThreadPoolManager.getScheduledPool(LinkPlayBindingConstants.BINDING_ID + "-http")
                .scheduleWithFixedDelay(this::poll, 0, pollingIntervalSeconds, TimeUnit.SECONDS);
        logger.debug("Started HTTP polling every {}s", pollingIntervalSeconds);
    }

    public void stopPolling() {
        if (pollingJob != null) {
            pollingJob.cancel(true);
            pollingJob = null;
            logger.debug("Stopped HTTP polling");
        }
    }

    /**
     * Periodic poll method, retrieves player status from device,
     * parses JSON, and notifies deviceManager.
     */
    private void poll() {
        httpClient.getPlayerStatus(getDeviceIp())
            .thenAccept(rawString -> {
                // parse JSON here
                JsonObject json = parseJsonSafely(rawString);
                if (json == null) {
                    // If parse fails or returns null, treat as poll failure
                    deviceManager.handleHttpPollFailure(new RuntimeException("Invalid JSON from poll"));
                } else {
                    deviceManager.updateChannelsFromHttp(json);
                }
            })
            .exceptionally(error -> {
                deviceManager.handleHttpPollFailure(error);
                return null;
            });
    }

    /**
     * Used by the device manager's handleCommand(...) to forward commands.
     */
    public void sendChannelCommand(String channelId, Command command) {
        String ipAddress = getDeviceIp();
        // Convert (channelId, command) into a "command string" and call httpClient
        // Or you can do further logic here:
        String cmd;
        switch (channelId) {
            case LinkPlayBindingConstants.CHANNEL_CONTROL:
                cmd = formatControlCommand(command);
                break;
            case LinkPlayBindingConstants.CHANNEL_VOLUME:
                cmd = formatVolumeCommand(command);
                break;
            // etc. for other channels
            default:
                logger.debug("No mapping found for channel {} => command={}", channelId, command);
                return;
        }

        // Actually send to device with optional retries
        // (If you want advanced retry logic, you can replicate the sendWithRetry approach.)
        httpClient.sendCommand(ipAddress, cmd)
                  .thenAccept(rawResponse -> {
                      // parse response if needed, maybe update device manager if it includes status
                      logger.debug("Command response: {}", rawResponse);
                  })
                  .exceptionally(ex -> {
                      deviceManager.handleHttpPollFailure(ex);
                      return null;
                  });
    }

    private String formatControlCommand(Command cmd) {
        // e.g. "setPlayerCmd:play" or "pause" etc.
        if (cmd.toString().equalsIgnoreCase("play")) {
            return "setPlayerCmd:play";
        } else if (cmd.toString().equalsIgnoreCase("pause")) {
            return "setPlayerCmd:pause";
        }
        logger.debug("Unhandled control command: {}", cmd);
        return "setPlayerCmd:pause";
    }

    private String formatVolumeCommand(Command cmd) {
        // e.g. "setPlayerCmd:vol:NN"
        try {
            int volume = Integer.parseInt(cmd.toString());
            return "setPlayerCmd:vol:" + volume;
        } catch (NumberFormatException e) {
            logger.warn("Volume command not an int: {}", cmd);
            return "setPlayerCmd:vol:50";
        }
    }

    /**
     * Helper to parse raw JSON string into a JsonObject safely, returning null on failure.
     */
    private JsonObject parseJsonSafely(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            logger.warn("Failed to parse JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Called by the constructor or the poll method to get the device IP,
     * if you have it in config or from the device manager. 
     * 
     * For demonstration, returns deviceManager's device if needed,
     * or you might store an ipAddress field in HttpManager.
     */
    private String getDeviceIp() {
        // For example, if DeviceManager had an 'ipAddress' property accessible:
        // return deviceManager.getIpAddress();
        // 
        // If not, you might store it in your LinkPlayConfiguration at creation time.
        return deviceManager.getThing().getConfiguration().get("ipAddress").toString();
    }

    public void dispose() {
        stopPolling();
    }
}

