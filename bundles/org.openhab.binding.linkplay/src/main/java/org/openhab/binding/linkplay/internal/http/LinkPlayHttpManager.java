package org.openhab.binding.linkplay.internal.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;

/**
 * Refactored LinkPlayHttpManager with retry logic for HTTP commands.
 */
public class LinkPlayHttpManager {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayHttpManager.class);

    private final LinkPlayHttpClient httpClient;
    private final int maxRetries;
    private final long retryDelayMillis;
    private final LinkPlayConfiguration config;

    public LinkPlayHttpManager(LinkPlayHttpClient httpClient, LinkPlayConfiguration config) {
        this.httpClient = httpClient;
        this.config = config;
        this.maxRetries = config.getMaxRetries();
        this.retryDelayMillis = config.getRetryDelayMillis();
    }

    /**
     * Sends a command to the LinkPlay device with retry logic.
     *
     * @param ipAddress The IP address of the LinkPlay device.
     * @param command   The command to send.
     * @return A CompletableFuture containing the response as a String.
     */
    public CompletableFuture<String> sendCommandWithRetry(String ipAddress, String command) {
        return sendWithRetry(() -> httpClient.sendCommand(ipAddress, command), maxRetries, retryDelayMillis);
    }

    /**
     * Generic retry logic with exponential backoff.
     *
     * @param task            The task to execute.
     * @param retriesLeft     The number of retries remaining.
     * @param delayMillis     The initial delay between retries.
     * @return A CompletableFuture containing the task result.
     */
    private <T> CompletableFuture<T> sendWithRetry(RetryableTask<T> task, int retriesLeft, long delayMillis) {
        return task.execute().exceptionallyCompose(error -> {
            if (retriesLeft > 0) {
                long nextDelay = calculateExponentialBackoff(retriesLeft, delayMillis);
                logger.warn("Retrying task. Retries left: {}, Next attempt in {} ms", retriesLeft - 1, nextDelay);
                
                return CompletableFuture.delayedExecutor(nextDelay, TimeUnit.MILLISECONDS).thenCompose(ignored ->
                        sendWithRetry(task, retriesLeft - 1, delayMillis));
            }

            logger.error("Task failed after retries: {}", error.getMessage(), error);
            return CompletableFuture.failedFuture(error);
        });
    }

    /**
     * Calculates exponential backoff with jitter.
     *
     * @param retriesLeft The number of retries remaining.
     * @param baseDelay   The base delay in milliseconds.
     * @return The calculated delay.
     */
    private long calculateExponentialBackoff(int retriesLeft, long baseDelay) {
        long jitter = (long) (Math.random() * baseDelay);
        return baseDelay * (maxRetries - retriesLeft + 1) + jitter;
    }

    /**
     * Functional interface for retryable tasks.
     *
     * @param <T> The task result type.
     */
    @FunctionalInterface
    private interface RetryableTask<T> {
        CompletableFuture<T> execute();
    }
}
