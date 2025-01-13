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

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Parser for UPnP DIDL and LastChange XML content
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class DIDLParser {
    private static final Logger logger = LoggerFactory.getLogger(DIDLParser.class);

    private static final NamespaceContext NAMESPACE_CONTEXT = new NamespaceContext() {
        @Override
        public String getNamespaceURI(String prefix) {
            switch (prefix) {
                case "dc":
                    return "http://purl.org/dc/elements/1.1/";
                case "upnp":
                    return "urn:schemas-upnp-org:metadata-1-0/upnp/";
                default:
                    return null;
            }
        }

        @Override
        public String getPrefix(String namespaceURI) {
            return null;
        }

        @Override
        public Iterator<String> getPrefixes(String namespaceURI) {
            return null;
        }
    };

    public static class MetaData {
        public String title = "";
        public String artist = "";
        public String album = "";
        public String artworkUrl = "";

        @Override
        public String toString() {
            return String.format("MetaData [title=%s, artist=%s, album=%s, artworkUrl=%s]", title, artist, album,
                    artworkUrl);
        }
    }

    @Nullable
    public static Map<String, String> parseMetadata(String metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }

        try {
            Document doc = parseXML(metadata);
            Map<String, String> values = new HashMap<>();

            // Extract standard DIDL-Lite fields
            addIfNotNull(values, "title", extractValue(doc, "//dc:title"));
            addIfNotNull(values, "artist", extractValue(doc, "//dc:creator"));
            addIfNotNull(values, "album", extractValue(doc, "//upnp:album"));
            addIfNotNull(values, "albumArtUri", extractValue(doc, "//upnp:albumArtURI"));

            return values.isEmpty() ? null : values;
        } catch (Exception e) {
            logger.debug("Error parsing DIDL-Lite metadata: {}", e.getMessage());
            return null;
        }
    }

    private static void addIfNotNull(Map<String, String> map, String key, @Nullable String value) {
        if (value != null && !value.isEmpty()) {
            map.put(key, value);
        }
    }

    @Nullable
    public static Map<String, String> getAVTransportFromXML(String xml) {
        if (xml == null || xml.isEmpty()) {
            return null;
        }

        try {
            Document doc = parseXML(xml);
            Map<String, String> values = new HashMap<>();

            // Extract AVTransport specific fields
            addIfNotNull(values, "TransportState", extractValue(doc, "//TransportState"));
            addIfNotNull(values, "CurrentTrackMetaData", extractValue(doc, "//CurrentTrackMetaData"));
            addIfNotNull(values, "CurrentTrackDuration", extractValue(doc, "//CurrentTrackDuration"));
            addIfNotNull(values, "AVTransportURI", extractValue(doc, "//AVTransportURI"));
            addIfNotNull(values, "NextAVTransportURI", extractValue(doc, "//NextAVTransportURI"));

            return values.isEmpty() ? null : values;
        } catch (Exception e) {
            logger.debug("Error parsing AVTransport XML: {}", e.getMessage());
            return null;
        }
    }

    @Nullable
    public static Map<String, String> getRenderingControlFromXML(String xml) {
        if (xml == null || xml.isEmpty()) {
            return null;
        }

        try {
            Document doc = parseXML(xml);
            Map<String, String> values = new HashMap<>();

            // Extract RenderingControl specific fields
            addIfNotNull(values, "Volume", extractValue(doc, "//Volume"));
            addIfNotNull(values, "Mute", extractValue(doc, "//Mute"));
            addIfNotNull(values, "PresetNameList", extractValue(doc, "//PresetNameList"));
            addIfNotNull(values, "CurrentPreset", extractValue(doc, "//CurrentPreset"));

            return values.isEmpty() ? null : values;
        } catch (Exception e) {
            logger.debug("Error parsing RenderingControl XML: {}", e.getMessage());
            return null;
        }
    }

    private static String getXPathValue(XPath xpath, String expression, Document doc) {
        try {
            return (String) xpath.evaluate(expression, doc, XPathConstants.STRING);
        } catch (Exception e) {
            return "";
        }
    }

    @Nullable
    private static String extractValue(Document doc, String xpathExpression) {
        try {
            XPath xPath = XPathFactory.newInstance().newXPath();
            xPath.setNamespaceContext(NAMESPACE_CONTEXT);
            Node node = (Node) xPath.evaluate(xpathExpression, doc, XPathConstants.NODE);
            return node != null ? node.getTextContent() : null;
        } catch (XPathExpressionException e) {
            logger.debug("Error evaluating XPath expression '{}': {}", xpathExpression, e.getMessage());
            return null;
        }
    }

    private static Document parseXML(String xml) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Disable external entity processing
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }
}
