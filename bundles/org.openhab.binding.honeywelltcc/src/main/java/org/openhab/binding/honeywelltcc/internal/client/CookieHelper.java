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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.api.Response;
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
    private final Map<String, HttpCookie> cookieStore = new ConcurrentHashMap<>();

    /**
     * Extracts and updates the internal cookie store from the given HTTP response.
     * Expired cookies are automatically removed.
     *
     * @param response the Jetty Response containing Set-Cookie headers
     */

    public void updateCookiesFromResponse(Response response) {
        List<String> setCookieHeaders = response.getHeaders().getValuesList("Set-Cookie");
        for (String header : setCookieHeaders) {
            List<HttpCookie> cookies = HttpCookie.parse(header);
            for (HttpCookie cookie : cookies) {
                // Skip cookies with empty or null value
                if (cookie.getValue() == null || cookie.getValue().trim().isEmpty()) {
                    cookieStore.remove(cookie.getName());
                    logger.debug("Removed or skipped cookie with empty value: {}", cookie.getName());
                    continue;
                }
                // Remove expired cookies.
                if (cookie.getMaxAge() == 0) {
                    cookieStore.remove(cookie.getName());
                    logger.debug("Removed expired cookie: {}", cookie.getName());
                } else {
                    cookieStore.put(cookie.getName(), cookie);
                    logger.debug("Updated cookie: {}={}", cookie.getName(), cookie.getValue());
                }
            }
        }
    }

    /**
     * Constructs the Cookie header string for a given URL by filtering stored cookies
     * based on domain and path. Also filters out problematic cookies (e.g. ASPXAUTH_TRUEHOME_RT).
     *
     * @param url the URL for which to construct the cookie header
     * @return a properly formatted Cookie header string to be used in HTTP requests
     */
    public String getCookieHeader(String url) {
        try {
            URL requestUrl = new URL(url);
            String cookieHeader = cookieStore.values().stream().filter(cookie -> {
                boolean domainMatches = HttpCookie.domainMatches(cookie.getDomain(), requestUrl.getHost());
                String cookiePath = (cookie.getPath() == null || cookie.getPath().isEmpty()) ? "/" : cookie.getPath();
                boolean pathMatches = requestUrl.getPath().startsWith(cookiePath);
                // Exclude problematic cookies if necessary.
                return domainMatches && pathMatches
                        && !cookie.getName().replaceFirst("^\\.", "").equalsIgnoreCase("ASPXAUTH_TRUEHOME_RT");
            }).map(cookie -> cookie.getName() + "=" + cookie.getValue()).collect(Collectors.joining("; "));
            logger.debug("Constructed Cookie header: {}", cookieHeader);
            return cookieHeader;
        } catch (MalformedURLException e) {
            logger.error("Malformed URL in getCookieHeader: {}", url, e);
            return "";
        }
    }
}
