/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.linkplay.internal.upnp;

import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Parser for DIDL-Lite and related UPnP XML content.
 * <p>
 * This class provides static methods to parse specific
 * DIDL-Lite or AVTransport/RenderingControl XML data structures,
 * returning key-value pairs for relevant metadata fields.
 * 
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class DIDLParser {

    private static final Logger logger = LoggerFactory.getLogger(DIDLParser.class);

    /**
     * Parses DIDL-Lite metadata (e.g., track info, album, artist, etc.).
     *
     * @param metadata the DIDL-Lite XML string
     * @return A map of known fields (title, artist, album, albumArtUri) or null if parse fails or input is empty
     */
    @Nullable
    public static Map<String, String> parseMetadata(String metadata) {
        if (metadata.isEmpty()) {
            return null;
        }

        MetadataHandler handler = new MetadataHandler();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            // Security features
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            // If you need namespace awareness, call factory.setNamespaceAware(true);

            SAXParser saxParser = factory.newSAXParser();
            saxParser.getXMLReader().setFeature("http://xml.org/sax/features/external-general-entities", false);

            saxParser.parse(new InputSource(new StringReader(metadata)), handler);
            return handler.getValues();
        } catch (IOException | SAXException | ParserConfigurationException e) {
            logger.debug("Error parsing DIDL-Lite metadata: {}", e.getMessage());
            // Optionally: logger.debug("Error parsing DIDL-Lite metadata", e);
            return null;
        }
    }

    /**
     * Parses AVTransport event XML, extracting fields like TransportState, CurrentTrackMetaData, etc.
     *
     * @param xml the AVTransport event XML
     * @return A map of recognized AVTransport fields, or null if parse fails or input is empty
     */
    @Nullable
    public static Map<String, String> getAVTransportFromXML(String xml) {
        if (xml.isEmpty()) {
            return null;
        }

        AVTransportHandler handler = new AVTransportHandler();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            // Security features
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

            SAXParser saxParser = factory.newSAXParser();
            saxParser.getXMLReader().setFeature("http://xml.org/sax/features/external-general-entities", false);

            saxParser.parse(new InputSource(new StringReader(xml)), handler);
            return handler.getValues();
        } catch (IOException | SAXException | ParserConfigurationException e) {
            logger.debug("Error parsing AVTransport XML: {}", e.getMessage());
            // Optionally: logger.debug("Error parsing AVTransport XML", e);
            return null;
        }
    }

    /**
     * Parses RenderingControl event XML, extracting fields like Volume, Mute, etc.
     *
     * @param xml the RenderingControl event XML
     * @return A map of recognized fields, or null if parse fails or input is empty
     */
    @Nullable
    public static Map<String, String> getRenderingControlFromXML(String xml) {
        if (xml.isEmpty()) {
            return null;
        }

        RenderingControlHandler handler = new RenderingControlHandler();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            // Security features
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

            SAXParser saxParser = factory.newSAXParser();
            saxParser.getXMLReader().setFeature("http://xml.org/sax/features/external-general-entities", false);

            saxParser.parse(new InputSource(new StringReader(xml)), handler);
            return handler.getValues();
        } catch (IOException | SAXException | ParserConfigurationException e) {
            logger.debug("Error parsing RenderingControl XML: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------------
    // Internal Handler for DIDL-Lite metadata (title, artist, album, albumArtUri)
    // ------------------------------------------------------------------------
    private static class MetadataHandler extends DefaultHandler {

        private final Map<String, String> values = new HashMap<>();
        private final StringBuilder currentValue = new StringBuilder();
        private String currentElement = "";

        @Override
        public void startElement(@Nullable String uri, @Nullable String localName, @Nullable String qName,
                @Nullable Attributes attributes) {
            if (qName == null) {
                return;
            }
            currentValue.setLength(0);
            currentElement = qName;

            // If an attribute "val" is present, store it directly
            if (attributes != null && attributes.getValue("val") != null) {
                addIfNotEmpty(values, qName, attributes.getValue("val"));
            }
        }

        @Override
        public void endElement(@Nullable String uri, @Nullable String localName, @Nullable String qName) {
            if (qName == null) {
                return;
            }
            // We only store text for certain recognized DIDL-Lite fields
            switch (currentElement) {
                case "dc:title":
                    addIfNotEmpty(values, "title", currentValue.toString());
                    break;
                case "dc:creator":
                    addIfNotEmpty(values, "artist", currentValue.toString());
                    break;
                case "upnp:album":
                    addIfNotEmpty(values, "album", currentValue.toString());
                    break;
                case "upnp:albumArtURI":
                    addIfNotEmpty(values, "albumArtUri", currentValue.toString());
                    break;
                default:
                    // ignore other elements
                    break;
            }
            currentElement = "";
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            currentValue.append(ch, start, length);
        }

        public Map<String, String> getValues() {
            return values.isEmpty() ? Collections.emptyMap() : values;
        }
    }

    // ------------------------------------------------------------------------
    // Internal Handler for AVTransport event fields (TransportState, etc.)
    // ------------------------------------------------------------------------
    private static class AVTransportHandler extends DefaultHandler {

        private final Map<String, String> values = new HashMap<>();
        private final StringBuilder currentValue = new StringBuilder();
        private String currentElement = "";

        @Override
        public void startElement(@Nullable String uri, @Nullable String localName, @Nullable String qName,
                @Nullable Attributes attributes) {
            if (qName == null) {
                return;
            }
            currentValue.setLength(0);
            currentElement = qName;

            // If attribute "val" is present, store directly
            if (attributes != null && attributes.getValue("val") != null) {
                addIfNotEmpty(values, qName, attributes.getValue("val"));
            }
        }

        @Override
        public void endElement(@Nullable String uri, @Nullable String localName, @Nullable String qName) {
            if (qName == null) {
                return;
            }
            // We store recognized AVTransport fields
            switch (currentElement) {
                case "TransportState":
                case "CurrentTrackMetaData":
                case "CurrentTrackDuration":
                case "AVTransportURI":
                case "NextAVTransportURI":
                    addIfNotEmpty(values, currentElement, currentValue.toString());
                    break;
                default:
                    // ignore unknown
                    break;
            }
            currentElement = "";
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            currentValue.append(ch, start, length);
        }

        public Map<String, String> getValues() {
            return values.isEmpty() ? Collections.emptyMap() : values;
        }
    }

    // ------------------------------------------------------------------------
    // Internal Handler for RenderingControl event fields (Volume, Mute, etc.)
    // ------------------------------------------------------------------------
    private static class RenderingControlHandler extends DefaultHandler {

        private final Map<String, String> values = new HashMap<>();
        private final StringBuilder currentValue = new StringBuilder();
        private String currentElement = "";

        @Override
        public void startElement(@Nullable String uri, @Nullable String localName, @Nullable String qName,
                @Nullable Attributes attributes) {
            if (qName == null) {
                return;
            }
            currentValue.setLength(0);
            currentElement = qName;

            if (attributes != null && attributes.getValue("val") != null) {
                addIfNotEmpty(values, qName, attributes.getValue("val"));
            }
        }

        @Override
        public void endElement(@Nullable String uri, @Nullable String localName, @Nullable String qName) {
            if (qName == null) {
                return;
            }
            switch (currentElement) {
                case "Volume":
                case "Mute":
                case "PresetNameList":
                case "CurrentPreset":
                    addIfNotEmpty(values, currentElement, currentValue.toString());
                    break;
                default:
                    // ignore unknown
                    break;
            }
            currentElement = "";
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            currentValue.append(ch, start, length);
        }

        public Map<String, String> getValues() {
            return values.isEmpty() ? Collections.emptyMap() : values;
        }
    }

    /**
     * Helper method to store a trimmed value in the map if it's non-empty.
     */
    private static void addIfNotEmpty(Map<String, String> map, String key, String value) {
        String trimmed = value.trim();
        if (!trimmed.isEmpty()) {
            map.put(key, trimmed);
        }
    }
}
