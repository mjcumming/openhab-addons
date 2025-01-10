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
package org.openhab.binding.linkplay.internal.parser;

import java.util.HashMap;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LinkPlayResponseParser} is responsible for parsing JSON responses
 * from LinkPlay devices and extracting specific values.
 *
 * @author Michael Cumming
 */
public class LinkPlayResponseParser {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayResponseParser.class);

    /**
     * Parses a JSON string and extracts the values based on the provided keys.
     *
     * @param jsonString The JSON string to parse.
     * @param keys The keys to extract from the JSON.
     * @return A map of key-value pairs extracted from the JSON.
     */
    public Map<String, String> parse(String jsonString, String... keys) {
        Map<String, String> extractedValues = new HashMap<>();
        try {
            JSONObject jsonObject = new JSONObject(jsonString);
            for (String key : keys) {
                if (jsonObject.has(key)) {
                    extractedValues.put(key, jsonObject.getString(key));
                } else {
                    logger.debug("Key '{}' not found in JSON response", key);
                }
            }
        } catch (JSONException e) {
            logger.warn("Error parsing JSON response: {}", e.getMessage(), e);
        }
        return extractedValues;
    }
}
