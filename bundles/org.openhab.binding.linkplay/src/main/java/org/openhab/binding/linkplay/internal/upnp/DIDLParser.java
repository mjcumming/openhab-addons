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
package org.openhab.binding.linkplay.internal.upnp;

import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Parser for DIDL-Lite metadata from UPnP events.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class DIDLParser {
    private static final Logger logger = LoggerFactory.getLogger(DIDLParser.class);

    private static final Map<String, String> NAMESPACE_MAP = new HashMap<>();
    static {
        NAMESPACE_MAP.put("dc", "http://purl.org/dc/elements/1.1/");
        NAMESPACE_MAP.put("upnp", "urn:schemas-upnp-org:metadata-1-0/upnp/");
        NAMESPACE_MAP.put("didl", "urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/");
        NAMESPACE_MAP.put("dlna", "urn:schemas-dlna-org:metadata-1-0/");
    }

    public static class MetaData {
        // Primary fields used by LinkPlay
        public String title = "";
        public String artist = "";
        public String album = "";
        public String duration = "";
        public String artworkUrl = ""; // Used for album art in LinkPlay

        // Additional fields (maintained for future extensibility)
        public String genre = "";
        public String streamUrl = "";
        public String serviceId = "";
        public Map<String, String> additionalProperties = new HashMap<>();
    }

    public static MetaData parseMetadata(String metadata) {
        MetaData result = new MetaData();
        if (metadata.isEmpty() || !metadata.contains("DIDL-Lite")) {
            return result;
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(metadata)));

            Element root = doc.getDocumentElement();
            NodeList items = root.getElementsByTagName("item");
            if (items.getLength() > 0) {
                Element item = (Element) items.item(0);

                // Parse primary fields used by LinkPlay
                result.title = getElementValue(item, "dc:title");
                result.artist = getElementValue(item, "dc:creator");
                result.album = getElementValue(item, "upnp:album");
                result.duration = getElementValue(item, "upnp:duration");
                result.artworkUrl = getElementValue(item, "upnp:albumArtURI");

                logger.debug("Parsed metadata - Title: {}, Artist: {}, Album: {}, Duration: {}, ArtworkUrl: {}",
                        result.title, result.artist, result.album, result.duration, result.artworkUrl);
            }
        } catch (Exception e) {
            logger.warn("Error parsing DIDL-Lite metadata: {}", e.getMessage());
        }
        return result;
    }

    private static String getElementValue(Element parent, String tagName) {
        String prefix = tagName.split(":")[0];
        String localName = tagName.split(":")[1];
        String namespace = NAMESPACE_MAP.get(prefix);

        if (namespace != null) {
            NodeList nodes = parent.getElementsByTagNameNS(namespace, localName);
            if (nodes.getLength() > 0) {
                Node node = nodes.item(0);
                return node.getTextContent();
            }
        }
        return "";
    }
}
