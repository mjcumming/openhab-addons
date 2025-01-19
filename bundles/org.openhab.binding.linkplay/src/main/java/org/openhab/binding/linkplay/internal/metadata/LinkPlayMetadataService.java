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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.LinkPlayDeviceManager;
import org.openhab.binding.linkplay.internal.transport.http.LinkPlayHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
     * Retrieves cover art and metadata for a track from MusicBrainz/CoverArtArchive
     *
     * @param artist The track artist
     * @param title The track title
     * @return Optional containing the cover art URL if found
     */
    public Optional<String> retrieveMusicMetadata(@Nullable String artist, @Nullable String title) {
        if (artist == null || title == null) {
            logger.debug("[{}] Artist or title is missing, cannot fetch metadata",
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
            // URL encode the artist and title parameters
            String encodedTitle = java.net.URLEncoder.encode(title, "UTF-8");
            String encodedArtist = java.net.URLEncoder.encode(artist, "UTF-8");

            // Query MusicBrainz with properly encoded parameters
            String mbUrl = String.format(
                    "https://musicbrainz.org/ws/2/recording?query=title:%s%%20AND%%20artist:%s&fmt=json", encodedTitle,
                    encodedArtist);
            logger.debug("[{}] Querying MusicBrainz: {}", deviceManager.getConfig().getDeviceName(), mbUrl);
            CompletableFuture<@Nullable String> futureMb = httpClient.rawGetRequest(mbUrl);

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
            logger.warn("[{}] Error fetching metadata: {}", deviceManager.getConfig().getDeviceName(), e.getMessage());
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
        try {
            String caaUrl = "https://coverartarchive.org/release/" + releaseId;
            CompletableFuture<@Nullable String> futureCaa = httpClient.rawGetRequest(caaUrl);
            String caaResponse = futureCaa.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (caaResponse != null) {
                JsonObject caaJson = JsonParser.parseString(caaResponse).getAsJsonObject();
                if (caaJson.has("images")) {
                    JsonArray images = caaJson.getAsJsonArray("images");
                    for (JsonElement image : images) {
                        JsonObject imageObj = image.getAsJsonObject();
                        if (imageObj.has("front") && imageObj.get("front").getAsBoolean()) {
                            return imageObj.get("image").getAsString();
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("[{}] Error retrieving cover art: {}", deviceManager.getConfig().getDeviceName(),
                    e.getMessage());
        }
        return null;
    }

    /**
     * Clear the metadata cache
     */
    public void clearCache() {
        metadataCache.clear();
        logger.debug("[{}] Metadata cache cleared", deviceManager.getConfig().getDeviceName());
    }
}
