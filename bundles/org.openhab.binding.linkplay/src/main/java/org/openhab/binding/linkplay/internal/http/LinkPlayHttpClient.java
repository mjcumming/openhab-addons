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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.linkplay.internal.model.MultiroomInfo;
import org.openhab.binding.linkplay.internal.model.PlayerStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The {@link LinkPlayHttpClient} is responsible for handling HTTP communication with LinkPlay devices
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayHttpClient {
    private final Logger logger = LoggerFactory.getLogger(LinkPlayHttpClient.class);
    private final HttpClient httpClient;
    private static final int REQUEST_TIMEOUT = 5;
    private static final int CONNECT_TIMEOUT = 5;

    public LinkPlayHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    private CompletableFuture<String> sendAsyncRequest(String url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ContentResponse response = httpClient.newRequest(url).timeout(REQUEST_TIMEOUT, TimeUnit.SECONDS)
                        .idleTimeout(REQUEST_TIMEOUT, TimeUnit.SECONDS).send();
                int status = response.getStatus();

                if (status == HttpStatus.OK_200) {
                    String content = response.getContentAsString();
                    logger.debug("Response from {}: {}", url, content);
                    return content;
                } else if (status == HttpStatus.NOT_FOUND_404) {
                    throw new LinkPlayApiException(String.format("Endpoint not supported: %s (HTTP 404)", url));
                } else if (status >= HttpStatus.INTERNAL_SERVER_ERROR_500) {
                    throw new LinkPlayApiException(String.format("Device error: HTTP %d", status));
                } else {
                    throw new LinkPlayApiException(String.format("Unexpected response: HTTP %d", status));
                }
            } catch (LinkPlayException e) {
                throw e;
            } catch (Exception e) {
                throw new LinkPlayCommunicationException(
                        String.format("Connection error: %s - %s", url, e.getMessage()));
            }
        });
    }

    private CompletableFuture<String> sendRequest(String ipAddress, String command) {
        String url = String.format("http://%s%s", ipAddress, command);
        logger.debug("Sending request to {}", url);
        return sendAsyncRequest(url);
    }

    public CompletableFuture<PlayerStatus> getPlayerStatus(String ipAddress) {
        return sendRequest(ipAddress, "/status").thenApply(response -> {
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            return parsePlayerStatus(json);
        });
    }

    public CompletableFuture<JsonObject> getStatusEx(String ipAddress) {
        return sendRequest(ipAddress, "/statusEx").thenApply(response -> {
            return JsonParser.parseString(response).getAsJsonObject();
        });
    }

    private PlayerStatus parsePlayerStatus(JsonObject json) {
        PlayerStatus.Builder builder = new PlayerStatus.Builder().withVolume(getIntValue(json, "vol"))
                .withMute(getBooleanValue(json, "mute")).withPlayStatus(String.valueOf(getIntValue(json, "status")));

        if (json.has("Title")) {
            builder.withTitle(json.get("Title").getAsString());
        }
        if (json.has("Artist")) {
            builder.withArtist(json.get("Artist").getAsString());
        }
        if (json.has("Album")) {
            builder.withAlbum(json.get("Album").getAsString());
        }

        return builder.build();
    }

    private int getIntValue(JsonObject json, String key) {
        return json.has(key) ? json.get(key).getAsInt() : 0;
    }

    private boolean getBooleanValue(JsonObject json, String key) {
        return json.has(key) && json.get(key).getAsInt() == 1;
    }

    // Command methods
    public CompletableFuture<Void> sendCommand(String ipAddress, String command) {
        return sendRequest(ipAddress, "/httpapi.asp?command=" + command).thenAccept(response -> {
            // Response handling if needed
        });
    }

    public CompletableFuture<MultiroomInfo> getMultiroomStatus(String ipAddress) {
        return sendRequest(ipAddress, "/multiroom/getStatus").thenApply(response -> {
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            return parseMultiroomInfo(json);
        });
    }

    public CompletableFuture<Void> joinGroup(String ipAddress, String masterIP) {
        return sendCommand(ipAddress, String.format("multiroom/join?master=%s", masterIP));
    }

    public CompletableFuture<Void> leaveGroup(String ipAddress) {
        return sendCommand(ipAddress, "multiroom/leave");
    }

    public CompletableFuture<Void> ungroup(String ipAddress) {
        return sendCommand(ipAddress, "multiroom/ungroup");
    }

    public CompletableFuture<Void> kickoutSlave(String ipAddress, String slaveIP) {
        return sendCommand(ipAddress, String.format("multiroom/kickout?slave=%s", slaveIP));
    }

    private MultiroomInfo parseMultiroomInfo(JsonObject json) {
        MultiroomInfo.Builder builder = new MultiroomInfo.Builder();

        if (json.has("type")) {
            builder.withRole(json.get("type").getAsString());
        }
        if (json.has("master_ip")) {
            builder.withMasterIP(json.get("master_ip").getAsString());
        }
        if (json.has("slave_list") && json.get("slave_list").isJsonArray()) {
            List<String> slaveIPs = new ArrayList<>();
            json.get("slave_list").getAsJsonArray().forEach(element -> {
                if (element.isJsonObject()) {
                    JsonObject slaveObj = element.getAsJsonObject();
                    if (slaveObj.has("ip")) {
                        slaveIPs.add(slaveObj.get("ip").getAsString());
                    }
                }
            });
            builder.withSlaveIPs(slaveIPs);
        }

        return builder.build();
    }

    public CompletableFuture<Void> setGroupVolume(String masterIp, List<String> slaveIps, int volume) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        // Send to master
        futures.add(sendCommand(masterIp, String.format("setPlayerCmd:vol:%d", volume)));
        // Send to each slave
        for (String slaveIp : slaveIps) {
            futures.add(sendCommand(slaveIp, String.format("setPlayerCmd:vol:%d", volume)));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    public CompletableFuture<Void> setGroupMute(String masterIp, List<String> slaveIps, boolean mute) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        // Send to master
        futures.add(sendCommand(masterIp, String.format("setPlayerCmd:mute:%d", mute ? 1 : 0)));
        // Send to each slave
        for (String slaveIp : slaveIps) {
            futures.add(sendCommand(slaveIp, String.format("setPlayerCmd:mute:%d", mute ? 1 : 0)));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
}
