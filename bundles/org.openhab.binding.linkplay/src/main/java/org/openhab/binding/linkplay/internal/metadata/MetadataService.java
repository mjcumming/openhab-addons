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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.DeviceManager;
import org.openhab.binding.linkplay.internal.transport.http.LinkPlayHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The {@link MetadataService} is responsible for retrieving music metadata and cover art from external services.
 * It implements caching and rate limiting to avoid excessive API calls. The service uses MusicBrainz for track
 * lookup and CoverArtArchive for album artwork retrieval.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class MetadataService {
    private final Logger logger = LoggerFactory.getLogger(MetadataService.class);
    private final LinkPlayHttpClient httpClient;
    private final DeviceManager deviceManager;

    // API endpoints
    private static final String MUSICBRAINZ_API_URL = "https://musicbrainz.org/ws/2/recording";
    private static final String COVERART_API_URL = "https://coverartarchive.org/release/%s";

    // Configuration constants
    private static final int TIMEOUT_MS = 5000; // Timeout for external API calls
    private static final Duration RATE_LIMIT = Duration.ofSeconds(2); // Minimum time between API requests
    private static final Duration CACHE_DURATION = Duration.ofHours(24); // How long to cache metadata

    private @Nullable Instant lastRequestTime;
    private final Map<String, CachedMetadata> metadataCache = new ConcurrentHashMap<>();

    /**
     * Represents cached metadata with timestamp for expiration checking
     */
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

    /**
     * Creates a new instance of the metadata service.
     *
     * @param httpClient The HTTP client for making external API calls
     * @param deviceManager The device manager for accessing device configuration
     */
    public MetadataService(LinkPlayHttpClient httpClient, DeviceManager deviceManager) {
        this.httpClient = httpClient;
        this.deviceManager = deviceManager;
    }

    /**
     * Retrieves music metadata including album art URL for the given artist and title.
     * Implements caching and rate limiting to minimize external API calls.
     *
     * @param artist The artist name
     * @param title The track title
     * @return Optional containing album art URL if found, empty if no metadata available or if rate limited
     */
    public Optional<String> retrieveMusicMetadata(String artist, String title) {
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
            // Build MusicBrainz query URL with proper URL encoding
            String url = buildMusicBrainzQuery(title, artist);

            logger.debug("[{}] Querying MusicBrainz: {}", deviceManager.getConfig().getDeviceName(), url);
            CompletableFuture<@Nullable String> futureMb = httpClient.rawGetRequest(url);

            @Nullable
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

    /**
     * Extracts the first available release ID from a MusicBrainz API response.
     * 
     * @param mbJson The JSON response from MusicBrainz API
     * @return The release ID if found, empty string otherwise
     */
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

    /**
     * Retrieves the cover art URL from CoverArtArchive for a given release ID.
     * Makes an HTTP request to the CoverArtArchive API and extracts the first available image URL.
     *
     * @param releaseId The MusicBrainz release ID to look up
     * @return The cover art URL if found, null otherwise
     */
    private @Nullable String retrieveCoverArtUrl(String releaseId) {
        try {
            String url = buildCoverArtUrl(releaseId);
            CompletableFuture<@Nullable String> future = httpClient.rawGetRequest(url);
            String response = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (response != null && !response.isEmpty()) {
                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                JsonArray images = json.getAsJsonArray("images");
                if (images != null && images.size() > 0) {
                    JsonObject image = images.get(0).getAsJsonObject();
                    if (image.has("image")) {
                        return image.get("image").getAsString();
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to retrieve cover art for release {}: {}", releaseId, e.getMessage());
        }
        return null;
    }

    /**
     * Clears the metadata cache, forcing fresh metadata retrieval on next request.
     * This can be useful when testing or when cached data becomes invalid.
     */
    public void clearCache() {
        metadataCache.clear();
        logger.debug("[{}] Metadata cache cleared", deviceManager.getConfig().getDeviceName());
    }

    private String buildMusicBrainzQuery(String title, String artist) throws Exception {
        String encodedTitle = URLEncoder.encode("title:" + title, StandardCharsets.UTF_8.name());
        String encodedArtist = URLEncoder.encode("artist:" + artist, StandardCharsets.UTF_8.name());
        String query = encodedTitle + "%20AND%20" + encodedArtist;
        return String.format("%s?query=%s&fmt=json", MUSICBRAINZ_API_URL, query);
    }

    private String buildCoverArtUrl(String releaseId) {
        return String.format(COVERART_API_URL, releaseId);
    }
}
