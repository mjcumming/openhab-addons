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

 package org.openhab.binding.linkplay.internal.http;

 import java.util.concurrent.CompletableFuture;
 import java.util.concurrent.ScheduledFuture;
 import java.util.concurrent.TimeUnit;
 
 import org.eclipse.jdt.annotation.NonNullByDefault;
 import org.eclipse.jdt.annotation.Nullable;
 import org.openhab.binding.linkplay.internal.LinkPlayBindingConstants;
 import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
 import org.openhab.binding.linkplay.internal.handler.LinkPlayDeviceManager;
 import org.openhab.core.common.ThreadPoolManager;
 import org.openhab.core.types.Command;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
 
 import com.google.gson.JsonObject;
 import com.google.gson.JsonParser;
 import com.google.gson.JsonSyntaxException;
 
 /**
  * The {@link LinkPlayHttpManager} handles HTTP communication with LinkPlay devices,
  * including polling, command sending, and optional retry logic.
  *
  * @author Michael Cumming - Initial contribution
  */
 @NonNullByDefault
 public class LinkPlayHttpManager {
 
     private static final Logger logger = LoggerFactory.getLogger(LinkPlayHttpManager.class);
 
     private final LinkPlayHttpClient httpClient;
     private final LinkPlayDeviceManager deviceManager;
     private final int maxRetries;
     private final long retryDelayMs;
     private final int pollingIntervalSeconds;
 
     private @Nullable ScheduledFuture<?> pollingJob;
     private static final int TIMEOUT_MS = 10000; // 10-second timeout
 
     // Keep original constants for max retries, delay, etc.
     private static final int MAX_RETRIES = 3;
     private static final int RETRY_DELAY_MS = 1000;
 
     private final LinkPlayConfiguration config;
 
     public LinkPlayHttpManager(LinkPlayHttpClient client, LinkPlayDeviceManager deviceManager,
             LinkPlayConfiguration config) {
         this.httpClient = client;
         this.deviceManager = deviceManager;
         this.config = config;
 
         // Use config-based values or fallback
         this.maxRetries = config.getMaxRetries() > 0 ? config.getMaxRetries() : MAX_RETRIES;
         this.retryDelayMs = config.getRetryDelayMillis() > 0 ? config.getRetryDelayMillis() : RETRY_DELAY_MS;
         this.pollingIntervalSeconds = Math.max(config.getPollingInterval(), 10);
 
         logger.debug("LinkPlayHttpManager created => maxRetries={}, retryDelayMs={}, pollInterval={}s",
                 this.maxRetries, this.retryDelayMs, this.pollingIntervalSeconds);
     }
 
     /**
      * Starts periodic polling using the configured interval.
      */
     public void startPolling() {
         if (pollingJob != null && !pollingJob.isCancelled()) {
             logger.debug("Polling is already running; skipping start.");
             return;
         }
 
         pollingJob = ThreadPoolManager.getScheduledPool(LinkPlayBindingConstants.BINDING_ID + "-http")
                 .scheduleWithFixedDelay(this::poll, 0, pollingIntervalSeconds, TimeUnit.SECONDS);
         logger.debug("Started HTTP polling every {}s", pollingIntervalSeconds);
     }
 
     /**
      * Stops periodic polling.
      */
     public void stopPolling() {
         if (pollingJob != null) {
             pollingJob.cancel(true);
             pollingJob = null;
             logger.debug("Stopped HTTP polling");
         }
     }
 
     /**
      * Periodic poll method: retrieves player status, parses JSON, and notifies deviceManager.
      */
     private void poll() {
         String ip = getDeviceIp();
         logger.trace("Polling device at IP={}", ip);
 
         try {
             CompletableFuture<@Nullable String> future = httpClient.getPlayerStatus(ip)
                     .handle((@Nullable String result, @Nullable Throwable ex) -> {
                         if (ex != null) {
                             logger.debug("Error polling device at {} => {}", ip, ex.getMessage());
                             return null;
                         }
                         return result;
                     });
 
             @Nullable String response = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
 
             if (response == null || response.isEmpty()) {
                 logger.debug("Null or empty response from device IP={}", ip);
                 deviceManager.handleHttpPollFailure(new LinkPlayCommunicationException("No response from device"));
                 return;
             }
 
             try {
                 JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                 logger.trace("poll() -> JSON: {}", json);
                 deviceManager.updateChannelsFromHttp(json);
             } catch (JsonSyntaxException e) {
                 logger.warn("Failed to parse device response => {}", e.getMessage());
                 deviceManager.handleHttpPollFailure(e);
             }
         } catch (Exception e) {
             logger.warn("HTTP poll failed => {}", e.getMessage());
             deviceManager.handleHttpPollFailure(e);
         }
     }
 
     /**
      * Send a channel-based command to the device using one-shot logic.
      */
     public void sendChannelCommand(String channelId, Command command) {
         String ip = getDeviceIp();
         @Nullable String cmd = formatCommand(channelId, command);
         if (cmd == null) {
             logger.debug("No mapping for channel='{}' => command='{}'", channelId, command);
             return;
         }
 
         logger.debug("Sending command='{}' to IP={}", cmd, ip);
         try {
             CompletableFuture<@Nullable String> futureResponse = httpClient.sendCommand(ip, cmd)
                     .handle((@Nullable String result, @Nullable Throwable ex) -> {
                         if (ex != null) {
                             logger.debug("Error sending command='{}' => {}", cmd, ex.getMessage());
                             return null;
                         }
                         return result;
                     });
 
             @Nullable String response = futureResponse.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
             logger.trace("Command response => {}", response);
         } catch (Exception ex) {
             logger.warn("Exception sending command='{}' => {}", cmd, ex.getMessage());
             deviceManager.handleHttpPollFailure(ex);
         }
     }
 
     /**
      * Send command to device with retry logic. This method was in the original code,
      * so we preserve it here. It's not invoked automatically by sendChannelCommand,
      * but can be used for more robust calls if desired.
      */
     public @Nullable JsonObject sendCommandWithRetry(String command, @Nullable String expectedResponse) {
         int retries = 0;
         Exception lastException = null;
 
         while (retries < MAX_RETRIES) {
             try {
                 JsonObject response = sendCommand(command);
                 if (response != null) {
                     // If we do not expect a specific response or if it matches
                     if (expectedResponse == null || (response.has("status")
                             && expectedResponse.equals(response.get("status").getAsString()))) {
                         return response;
                     }
                 }
                 // Wrong or null response => try again
                 retries++;
                 if (retries < MAX_RETRIES) {
                     Thread.sleep(RETRY_DELAY_MS);
                 }
             } catch (Exception e) {
                 lastException = e;
                 retries++;
                 if (retries < MAX_RETRIES) {
                     try {
                         Thread.sleep(RETRY_DELAY_MS);
                     } catch (InterruptedException ie) {
                         Thread.currentThread().interrupt();
                         return null;
                     }
                 }
             }
         }
 
         if (lastException != null) {
             logger.debug("Command '{}' failed after {} retries => {}", command, MAX_RETRIES, lastException.getMessage());
         }
         return null; // No success
     }
 
     /**
      * Helper method that actually sends an HTTP command and parses JSON,
      * used by sendCommandWithRetry(...).
      */
     public @Nullable JsonObject sendCommand(String command) {
         String ip = getDeviceIp();
         try {
             CompletableFuture<@Nullable String> future = httpClient.sendCommand(ip, command)
                     .handle((@Nullable String result, @Nullable Throwable ex) -> {
                         if (ex != null) {
                             logger.debug("Error sending command='{}': {}", command, ex.getMessage());
                             return null;
                         }
                         return result;
                     });
 
             @Nullable String response = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
             if (response == null) {
                 return null;
             }
 
             try {
                 return JsonParser.parseString(response).getAsJsonObject();
             } catch (JsonSyntaxException e) {
                 logger.warn("Failed to parse response for command='{}': {}", command, e.getMessage());
                 deviceManager.handleHttpPollFailure(e);
                 return null;
             }
 
         } catch (Exception e) {
             logger.debug("Exception sending command='{}': {}", command, e.getMessage());
             return null;
         }
     }
 
     /**
      * Formats an HTTP command string based on channel + openHAB command.
      * Preserves all logic from original code, with small fixes for shuffle/repeat.
      */
     private @Nullable String formatCommand(String channelId, Command command) {
         switch (channelId) {
             case LinkPlayBindingConstants.CHANNEL_CONTROL:
                 return formatControlCommand(command);
 
             case LinkPlayBindingConstants.CHANNEL_VOLUME:
                 return formatVolumeCommand(command);
 
             case LinkPlayBindingConstants.CHANNEL_MUTE:
                 return formatMuteCommand(command);
 
             case LinkPlayBindingConstants.CHANNEL_REPEAT:
                 return formatRepeatCommand(command);
 
             case LinkPlayBindingConstants.CHANNEL_SHUFFLE:
                 return formatShuffleCommand(command);
 
             default:
                 return null;
         }
     }
 
     private String formatControlCommand(Command cmd) {
         // e.g. "setPlayerCmd:play" or "pause"
         String c = cmd.toString().toUpperCase();
         if ("PLAY".equals(c)) {
             return "setPlayerCmd:play";
         } else if ("PAUSE".equals(c)) {
             return "setPlayerCmd:pause";
         }
         logger.debug("Unhandled control command => {}", cmd);
         return null;
     }
 
     private String formatVolumeCommand(Command cmd) {
         // e.g. "setPlayerCmd:vol:NN"
         try {
             int volume = Integer.parseInt(cmd.toString());
             return "setPlayerCmd:vol:" + volume;
         } catch (NumberFormatException e) {
             logger.warn("Volume command not an integer => {}", cmd);
             return null;
         }
     }
 
     private String formatMuteCommand(Command cmd) {
         // e.g. "setPlayerCmd:mute:on" or :mute:off
         boolean isOn = cmd.toString().equalsIgnoreCase("ON");
         return "setPlayerCmd:mute:" + (isOn ? "on" : "off");
     }
 
     private String formatRepeatCommand(Command cmd) {
         // for on/off => loop:2 (on) or loop:0 (off)
         boolean isOn = cmd.toString().equalsIgnoreCase("ON");
         return "setPlayerCmd:loop:" + (isOn ? "2" : "0");
     }
 
     private String formatShuffleCommand(Command cmd) {
         // for on/off => random:1 or random:0
         boolean isOn = cmd.toString().equalsIgnoreCase("ON");
         return "setPlayerCmd:random:" + (isOn ? "1" : "0");
     }
 
     /**
      * Called by the constructor or poll method to get the device IP.
      */
     private String getDeviceIp() {
         String ip = config.getIpAddress();
         if (ip.isEmpty()) {
             logger.warn("Device IP address is empty. Using default IP '0.0.0.0'.");
             return "0.0.0.0";
         }
         return ip;
     }
 
     /**
      * Clean up any scheduling on disposal.
      */
     public void dispose() {
         stopPolling();
     }
 }
 