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
package org.openhab.binding.linkplay.internal.metadata;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.LinkPlayDeviceManager;
import org.openhab.binding.linkplay.internal.transport.http.LinkPlayHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Service for retrieving music metadata and cover art from external services
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayMetadataService {
    private final Logger logger = LoggerFactory.getLogger(LinkPlayMetadataService.class);
    private final LinkPlayHttpClient httpClient;
    private final LinkPlayDeviceManager deviceManager;
    private static final int TIMEOUT_MS = 5000;
    private static final Duration RATE_LIMIT = Duration.ofSeconds(2);
    private static final Duration CACHE_DURATION = Duration.ofHours(24);

    private @Nullable Instant lastRequestTime;
    private final Map<String, CachedMetadata> metadataCache = new ConcurrentHashMap<>();

    private static class CachedMetadata {
        final String url;
        final Instant timestamp;

        CachedMetadata(String url) {
            this.url = url;
            this.timestamp = Instant.now();
        }

        boolean isExpired() {
            return Duration.between(timestamp, Instant.now()).compareTo(CACHE_DURATION) > 0;
        }
    }

    public LinkPlayMetadataService(LinkPlayHttpClient httpClient, LinkPlayDeviceManager deviceManager) {
        this.httpClient = httpClient;
        this.deviceManager = deviceManager;
    }

    /**
     * Retrieve music metadata for the given artist and title
     * 
     * @param artist The artist name, must not be null
     * @param title The track title, must not be null
     * @return Optional containing album art URL if found
     */
    public Optional<String> retrieveMusicMetadata(@NonNull String artist, @NonNull String title) {
        // Skip metadata lookup for Unknown tracks
        if ("Unknown".equals(artist) || "Unknown".equals(title) || artist.isEmpty() || title.isEmpty()) {
            logger.debug("[{}] Skipping metadata lookup for Unknown/empty track",
                    deviceManager.getConfig().getDeviceName());
            return Optional.empty();
        }

        logger.debug("[{}] Attempting to fetch album art for artist='{}' title='{}'",
                deviceManager.getConfig().getDeviceName(), artist, title);

        // Check cache first
        String cacheKey = artist.toLowerCase() + "|" + title.toLowerCase();
        CachedMetadata cached = metadataCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            logger.debug("[{}] Returning cached cover art URL for {}/{}", deviceManager.getConfig().getDeviceName(),
                    artist, title);
            return Optional.of(cached.url);
        }

        // Apply rate limiting
        Instant now = Instant.now();
        Instant lastRequest = lastRequestTime;
        if (lastRequest != null) {
            Duration sinceLastRequest = Duration.between(lastRequest, now);
            if (sinceLastRequest.compareTo(RATE_LIMIT) < 0) {
                logger.debug("[{}] Rate limiting in effect, skipping metadata request",
                        deviceManager.getConfig().getDeviceName());
                return Optional.empty();
            }
        }
        lastRequestTime = now;

        try {
            // Build MusicBrainz query URL
            String query = String.format("title:%s AND artist:%s",
                    URLEncoder.encode(title, StandardCharsets.UTF_8.name()),
                    URLEncoder.encode(artist, StandardCharsets.UTF_8.name()));
            String url = String.format("https://musicbrainz.org/ws/2/recording?query=%s&fmt=json", query);

            logger.debug("[{}] Querying MusicBrainz: {}", deviceManager.getConfig().getDeviceName(), url);
            CompletableFuture<@Nullable String> futureMb = httpClient.rawGetRequest(url);

            String mbResponse = futureMb.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (mbResponse == null) {
                logger.debug("[{}] No MusicBrainz response", deviceManager.getConfig().getDeviceName());
                return Optional.empty();
            }

            JsonObject mbJson = JsonParser.parseString(mbResponse).getAsJsonObject();
            String releaseId = extractReleaseId(mbJson);
            if (releaseId.isEmpty()) {
                logger.debug("[{}] No release ID found for artist='{}' title='{}'",
                        deviceManager.getConfig().getDeviceName(), artist, title);
                return Optional.empty();
            }

            logger.debug("[{}] Found MusicBrainz release ID: {} for artist='{}' title='{}'",
                    deviceManager.getConfig().getDeviceName(), releaseId, artist, title);

            // Query CoverArtArchive
            String coverArtUrl = retrieveCoverArtUrl(releaseId);
            if (coverArtUrl != null) {
                logger.debug("[{}] Found cover art URL: {}", deviceManager.getConfig().getDeviceName(), coverArtUrl);
                // Cache the result
                metadataCache.put(cacheKey, new CachedMetadata(coverArtUrl));
                return Optional.of(coverArtUrl);
            } else {
                logger.debug("[{}] No cover art found for release ID: {}", deviceManager.getConfig().getDeviceName(),
                        releaseId);
            }

        } catch (Exception e) {
            logger.warn("[{}] Error retrieving metadata: {}", deviceManager.getConfig().getDeviceName(),
                    e.getMessage());
        }
        return Optional.empty();
    }

    private String extractReleaseId(JsonObject mbJson) {
        if (mbJson.has("recordings")) {
            JsonArray recordings = mbJson.getAsJsonArray("recordings");
            if (recordings.size() > 0) {
                JsonObject recording = recordings.get(0).getAsJsonObject();
                if (recording.has("releases")) {
                    JsonArray releases = recording.getAsJsonArray("releases");
                    if (releases.size() > 0) {
                        return releases.get(0).getAsJsonObject().get("id").getAsString();
                    }
                }
            }
        }
        logger.debug("[{}] No release ID found in MusicBrainz response", deviceManager.getConfig().getDeviceName());
        return "";
    }

    private @Nullable String retrieveCoverArtUrl(String releaseId) {
        // Return the direct front cover URL without verification
        return "https://coverartarchive.org/release/" + releaseId + "/front";
    }

    /**
     * Clear the metadata cache
     */
    public void clearCache() {
        metadataCache.clear();
        logger.debug("[{}] Metadata cache cleared", deviceManager.getConfig().getDeviceName());
    }
}
