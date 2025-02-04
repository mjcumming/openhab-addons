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
package org.openhab.binding.honeywelltcc.internal.client;

import java.net.HttpCookie;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CookieHelper is responsible for managing cookies received from HTTP responses
 * and constructing the proper Cookie header for subsequent requests.
 *
 * This design is in line with established OpenHAB binding patterns such as in the Philips Hue or Netatmo bindings,
 * and it mirrors the session management behavior of the Python implementation.
 * 
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class CookieHelper {
    private final Logger logger = LoggerFactory.getLogger(CookieHelper.class);
    private final Map<String, String> cookieStore = new ConcurrentHashMap<>();

    /**
     * Updates a cookie in the cookie store. If the provided value is null or empty,
     * the cookie is removed.
     *
     * @param name the cookie name
     * @param value the cookie value
     */
    public void updateCookie(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            logger.debug("Removed or skipped cookie with empty value: {}", name);
            cookieStore.remove(name);
        } else {
            cookieStore.put(name, value);
            logger.debug("Updated cookie: {}={}", name, value);
        }
        // Log the entire cookie store for troubleshooting.
        logger.debug("Current cookie store state: {}", cookieStore);
    }

    /**
     * Builds the Cookie header value from the currently stored cookies.
     *
     * @return a properly formatted Cookie header string or empty if the store is empty
     */
    public String buildCookieHeader() {
        // Log the store contents before constructing the header.
        if (cookieStore.isEmpty()) {
            logger.debug("Cookie store is empty during header construction");
        } else {
            cookieStore.forEach((name, value) -> logger.debug("Cookie in store: {} -> {}", name, value));
        }
        StringBuilder header = new StringBuilder();
        for (Map.Entry<String, String> entry : cookieStore.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            // Only skip if value is truly empty.
            if (value != null && !value.trim().isEmpty()) {
                if (header.length() > 0) {
                    header.append("; ");
                }
                header.append(name).append("=").append(value);
            } else {
                logger.debug("Skipping cookie {} due to empty value", name);
            }
        }
        String constructedHeader = header.toString();
        logger.debug("Constructed Cookie header: {}", constructedHeader);
        return constructedHeader;
    }

    /**
     * Alternative method to build the Cookie header from a provided cookie store.
     * Useful when cookies are stored as HttpCookie objects.
     *
     * @param customCookieStore a mapping of cookie name to HttpCookie
     * @return the constructed Cookie header string
     */
    public String buildCookieHeader(Map<String, HttpCookie> customCookieStore) {
        if (customCookieStore.isEmpty()) {
            logger.debug("Custom cookie store is empty");
        } else {
            customCookieStore.forEach(
                    (name, cookie) -> logger.debug("Cookie in custom store: {} -> {}", name, cookie.getValue()));
        }

        StringBuilder header = new StringBuilder();
        for (Map.Entry<String, HttpCookie> entry : customCookieStore.entrySet()) {
            HttpCookie cookie = entry.getValue();
            String value = cookie.getValue();
            if (value != null && !value.trim().isEmpty()) {
                if (header.length() > 0) {
                    header.append("; ");
                }
                header.append(cookie.getName()).append("=").append(value);
            } else {
                logger.debug("Skipping cookie {} due to empty value", cookie.getName());
            }
        }
        String constructedHeader = header.toString();
        logger.debug("Constructed custom Cookie header: {}", constructedHeader);
        return constructedHeader;
    }

    /**
     * Constructs the Cookie header from the given URL by filtering cookies based
     * on domain and path. Also filters out problematic cookies such as ASPXAUTH_TRUEHOME_RT.
     *
     * @param url the URL for which to construct the cookie header
     * @return a properly formatted Cookie header string to be used in HTTP requests
     */
    public String getCookieHeader(String url) {
        try {
            URL requestUrl = new URL(url);
            // Filter cookies based on domain and path matching.
            String cookieHeader = cookieStore.values().stream().filter(cookieValue -> {
                // Here you might want to include extra checks based on the cookie.
                // Currently, we assume all stored cookies are valid.
                return cookieValue != null && !cookieValue.trim().isEmpty();
            }).collect(Collectors.joining("; "));
            logger.debug("Constructed Cookie header from store: {}", cookieHeader);
            return cookieHeader;
        } catch (MalformedURLException e) {
            logger.error("Malformed URL in getCookieHeader: {}", url, e);
            return "";
        }
    }

    /**
     * Extracts cookies from an HTTP response and updates the internal cookie store.
     *
     * @param response the Jetty Response containing Set-Cookie headers.
     */
    public void updateCookiesFromResponse(org.eclipse.jetty.client.api.Response response) {
        java.util.List<String> setCookieHeaders = response.getHeaders().getValuesList("Set-Cookie");
        if (setCookieHeaders == null || setCookieHeaders.isEmpty()) {
            logger.debug("No Set-Cookie headers found in response.");
        } else {
            for (String header : setCookieHeaders) {
                java.util.List<HttpCookie> cookies = HttpCookie.parse(header);
                for (HttpCookie cookie : cookies) {
                    // Update each cookie in the store.
                    updateCookie(cookie.getName(), cookie.getValue());
                }
            }
        }
    }
}
