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
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.LinkPlayBindingConstants;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.types.State;
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
@NonNullByDefault
public class LinkPlayHttpManager {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayHttpManager.class);
    private static final String THREAD_POOL_NAME = LinkPlayBindingConstants.BINDING_ID + "-http";
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 1000;

    private final LinkPlayHttpClient httpClient;
    private final int maxRetries;
    private final long retryDelayMillis;

    /**
     * Constructs a new {@link LinkPlayHttpManager} with the given IP address.
     * Creates and manages its own HTTP client with default configuration.
     *
     * @param httpClient The HTTP client to use for communication
     * @param ipAddress The IP address of the LinkPlay device
     */
    public LinkPlayHttpManager(LinkPlayHttpClient httpClient, String ipAddress) {
        if (ipAddress.isEmpty()) {
            throw new IllegalArgumentException("IP address cannot be empty");
        }
        this.httpClient = httpClient;
        this.maxRetries = DEFAULT_MAX_RETRIES;
        this.retryDelayMillis = DEFAULT_RETRY_DELAY_MS;
        logger.debug("Initialized LinkPlayHttpManager with default settings - maxRetries={}, retryDelay={}ms",
                maxRetries, retryDelayMillis);
    }

    /**
     * Constructs a new {@link LinkPlayHttpManager}.
     *
     * @param httpClient The HTTP client to use for communication
     * @param config The binding configuration containing retry settings
     */
    protected LinkPlayHttpManager(LinkPlayHttpClient httpClient, LinkPlayConfiguration config) {
        this.httpClient = httpClient;
        this.maxRetries = config.getMaxRetries() > 0 ? config.getMaxRetries() : DEFAULT_MAX_RETRIES;
        this.retryDelayMillis = config.getRetryDelayMillis() > 0 ? config.getRetryDelayMillis()
                : DEFAULT_RETRY_DELAY_MS;

        logger.debug("Initialized LinkPlayHttpManager with maxRetries={}, retryDelay={}ms", maxRetries,
                retryDelayMillis);
    }

    /**
     * Sends a command to the LinkPlay device with retry logic.
     *
     * @param ipAddress The IP address of the LinkPlay device
     * @param command The command to send
     * @return A CompletableFuture containing the response as a String
     * @throws IllegalArgumentException if ipAddress or command is empty
     */
    public CompletableFuture<String> sendCommandWithRetry(String ipAddress, String command) {
        if (ipAddress.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("IP address cannot be empty"));
        }
        if (command.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Command cannot be empty"));
        }

        logger.debug("Sending command '{}' to device at {}", command, ipAddress);
        return sendWithRetry(() -> httpClient.sendCommand(ipAddress, command), maxRetries, retryDelayMillis)
                .exceptionally(error -> {
                    handleCommunicationError(error);
                    throw new CompletionException(
                            new LinkPlayCommunicationException("Failed to send command: " + error.getMessage(), error));
                });
    }

    /**
     * Generic retry logic with exponential backoff.
     *
     * @param task The task to execute
     * @param retriesLeft The number of retries remaining
     * @param delayMillis The initial delay between retries
     * @return A CompletableFuture containing the task result
     */
    private <T> CompletableFuture<T> sendWithRetry(RetryableTask<T> task, int retriesLeft, long delayMillis) {
        return task.execute().exceptionally(error -> {
            handleCommunicationError(error);

            if (retriesLeft <= 0) {
                throw new CompletionException(error);
            }

            long nextDelay = calculateExponentialBackoff(retriesLeft, delayMillis);
            logger.debug("Retrying request after {} ms ({} retries left)", nextDelay, retriesLeft);

            return CompletableFuture.supplyAsync(() -> {
                try {
                    return sendWithRetry(task, retriesLeft - 1, delayMillis).get();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, CompletableFuture.delayedExecutor(nextDelay, TimeUnit.MILLISECONDS,
                    ThreadPoolManager.getScheduledPool(THREAD_POOL_NAME))).join();
        });
    }

    /**
     * Calculates exponential backoff with jitter.
     *
     * @param retriesLeft The number of retries remaining
     * @param baseDelay The base delay in milliseconds
     * @return The calculated delay with jitter
     */
    private long calculateExponentialBackoff(int retriesLeft, long baseDelay) {
        double jitterFactor = 0.5 + Math.random(); // Random factor between 0.5 and 1.5
        long exponentialDelay = baseDelay * (1L << (maxRetries - retriesLeft));
        return (long) (exponentialDelay * jitterFactor);
    }

    /**
     * Handles communication errors by logging them.
     *
     * @param error The error that occurred
     */
    protected void handleCommunicationError(Throwable error) {
        String message = error.getMessage();
        if (message == null) {
            message = "Communication error: " + error.getClass().getSimpleName();
        }
        logger.warn("HTTP communication error: {}", message);
    }

    /**
     * Updates a channel state with the given value.
     * This implementation only logs the update attempt.
     *
     * @param channelId The ID of the channel to update
     * @param state The new state value
     */
    protected void updateChannelState(String channelId, State state) {
        logger.debug("Channel state update requested - channelId: {}, state: {}", channelId, state);
    }

    /**
     * Parses an HTTP response into a JSON object.
     *
     * @param response The HTTP response string
     * @return The parsed JSON object or null if parsing fails
     */
    protected @Nullable JsonObject parseHttpResponse(String response) {
        if (response.isEmpty()) {
            logger.debug("Empty response received");
            return null;
        }

        try {
            return JsonParser.parseString(response).getAsJsonObject();
        } catch (Exception e) {
            logger.warn("Failed to parse HTTP response: {}", e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("Parse error details:", e);
            }
            return null;
        }
    }

    /**
     * Functional interface for retryable tasks.
     *
     * @param <T> The task result type
     */
    @FunctionalInterface
    private interface RetryableTask<T> {
        CompletableFuture<T> execute();
    }

    /**
     * Disposes of the HTTP manager and releases resources.
     */
    public void dispose() {
        logger.debug("Disposing LinkPlayHttpManager");
        try {
            // Since httpClient is an OSGi component, we don't need to dispose it
            // It will be handled by the OSGi framework
            logger.debug("LinkPlayHttpManager disposed successfully");
        } catch (RuntimeException e) {
            logger.warn("Error during HTTP manager disposal: {}", e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("Disposal error details:", e);
            }
        }
    }
}
