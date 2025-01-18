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

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final String deviceId;
    private static final int TIMEOUT_MS = 5000;

    public LinkPlayMetadataService(LinkPlayHttpClient httpClient, String deviceId) {
        this.httpClient = httpClient;
        this.deviceId = deviceId;
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
            logger.debug("[{}] Artist or title is missing, cannot fetch metadata", deviceId);
            return Optional.empty();
        }

        try {
            // URL encode the artist and title parameters
            String encodedTitle = java.net.URLEncoder.encode(title, "UTF-8");
            String encodedArtist = java.net.URLEncoder.encode(artist, "UTF-8");

            // Query MusicBrainz with properly encoded parameters
            String mbUrl = String.format(
                    "https://musicbrainz.org/ws/2/recording?query=title:%s%%20AND%%20artist:%s&fmt=json", encodedTitle,
                    encodedArtist);
            CompletableFuture<@Nullable String> futureMb = httpClient.rawGetRequest(mbUrl);

            String mbResponse = futureMb.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (mbResponse == null) {
                logger.debug("[{}] No MusicBrainz response", deviceId);
                return Optional.empty();
            }

            JsonObject mbJson = JsonParser.parseString(mbResponse).getAsJsonObject();
            String releaseId = extractReleaseId(mbJson);
            if (releaseId.isEmpty()) {
                return Optional.empty();
            }

            // Query CoverArtArchive
            String coverArtUrl = retrieveCoverArtUrl(releaseId);
            return Optional.ofNullable(coverArtUrl);

        } catch (Exception e) {
            logger.warn("[{}] Error fetching metadata: {}", deviceId, e.getMessage());
            return Optional.empty();
        }
    }

    private String extractReleaseId(JsonObject mbJson) {
        if (mbJson.has("releases") && mbJson.getAsJsonArray("releases").size() > 0) {
            return mbJson.getAsJsonArray("releases").get(0).getAsJsonObject().get("id").getAsString();
        }
        logger.debug("[{}] No release ID found in MusicBrainz response", deviceId);
        return "";
    }

    private @Nullable String retrieveCoverArtUrl(String releaseId) {
        try {
            String caaUrl = "https://coverartarchive.org/release/" + releaseId;
            CompletableFuture<@Nullable String> futureCaa = httpClient.rawGetRequest(caaUrl);
            String caaResponse = futureCaa.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (caaResponse != null) {
                JsonObject caaJson = JsonParser.parseString(caaResponse).getAsJsonObject();
                // TODO: Parse cover art URL from response
                return null; // Replace with actual URL extraction
            }
        } catch (Exception e) {
            logger.debug("[{}] Error retrieving cover art: {}", deviceId, e.getMessage());
        }
        return null;
    }
}
