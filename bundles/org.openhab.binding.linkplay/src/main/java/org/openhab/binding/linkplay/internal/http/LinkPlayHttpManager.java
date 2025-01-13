package org.openhab.binding.linkplay.internal.http;

import java.util.concurrent.CompletableFuture;

import javax.net.ssl.SSLContext;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.linkplay.internal.LinkPlayBindingConstants;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.openhab.binding.linkplay.internal.utils.AsyncHttpClientUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages HTTP communication with LinkPlay devices, utilizing custom SSL configuration and PEM handling.
 */
@NonNullByDefault
public class LinkPlayHttpManager {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayHttpManager.class);

    private final LinkPlayHttpClient httpClient;
    private final LinkPlayConfiguration config;

    public LinkPlayHttpManager(LinkPlayHttpClient httpClient, LinkPlayConfiguration config) {
        this.httpClient = httpClient;
        this.config = config;
    }

    /**
     * Fetches the player status from the LinkPlay device.
     *
     * @return A CompletableFuture containing the response as a String.
     */
    public CompletableFuture<String> getPlayerStatus() {
        return sendCommand("getPlayerStatus");
    }

    /**
     * Fetches extended status information from the LinkPlay device.
     *
     * @return A CompletableFuture containing the response as a String.
     */
    public CompletableFuture<String> getStatusEx() {
        return sendCommand("getStatusEx");
    }

    /**
     * Sends a command to the LinkPlay device.
     *
     * @param command The API command to send.
     * @return A CompletableFuture containing the response as a String.
     */
    public CompletableFuture<String> sendCommand(String command) {
        String ipAddress = config.ipAddress;
        if (ipAddress == null || ipAddress.isEmpty()) {
            logger.warn("IP address is not configured.");
            return CompletableFuture.failedFuture(new IllegalStateException("IP address is not configured."));
        }

        String url = "http://" + ipAddress + "/httpapi.asp?command=" + command;
        logger.debug("Sending command to LinkPlay device: {}", url);

        try {
            SSLContext sslContext = httpClient.createSslContextFromPem(PemConstants.PEM_CONTENT);
            return AsyncHttpClientUtil.sendAsyncGetRequest(url, sslContext)
                    .thenApply(response -> {
                        logger.debug("Received response: {}", response);
                        return response;
                    });
        } catch (Exception e) {
            logger.error("Error creating SSLContext or sending request: {}", e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }
}
