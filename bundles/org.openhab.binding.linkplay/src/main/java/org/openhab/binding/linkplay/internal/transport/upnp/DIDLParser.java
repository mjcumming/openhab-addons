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
package org.openhab.binding.linkplay.internal.transport.upnp;

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
     */
    @Nullable
    public static Map<String, String> parseMetadata(String metadata) {
        if (metadata.isEmpty()) {
            return null;
        }
        MetadataHandler handler = new MetadataHandler();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

            SAXParser saxParser = factory.newSAXParser();
            saxParser.getXMLReader().setFeature("http://xml.org/sax/features/external-general-entities", false);
            saxParser.parse(new InputSource(new StringReader(metadata)), handler);
            return handler.getValues();
        } catch (IOException | SAXException | ParserConfigurationException e) {
            logger.debug("Error parsing DIDL-Lite metadata: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Example method to parse AVTransport event XML (non-LastChange style).
     * You already had something similar. We'll keep it for reference.
     */
    @Nullable
    public static Map<String, String> getAVTransportFromXML(String xml) {
        if (xml.isEmpty()) {
            return null;
        }
        AVTransportHandler handler = new AVTransportHandler();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

            SAXParser saxParser = factory.newSAXParser();
            saxParser.getXMLReader().setFeature("http://xml.org/sax/features/external-general-entities", false);
            saxParser.parse(new InputSource(new StringReader(xml)), handler);
            return handler.getValues();
        } catch (IOException | SAXException | ParserConfigurationException e) {
            logger.debug("Error parsing AVTransport XML: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Example method to parse RenderingControl event XML (non-LastChange style).
     */
    @Nullable
    public static Map<String, String> getRenderingControlFromXML(String xml) {
        if (xml.isEmpty()) {
            return null;
        }
        RenderingControlHandler handler = new RenderingControlHandler();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
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
    // NEW: parseLastChange(...) for "LastChange" event data
    // ------------------------------------------------------------------------

    /**
     * Parse a typical 'LastChange' event string used by AVTransport or RenderingControl
     * in many UPnP devices. The XML structure often looks like:
     *
     * <Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/">
     * <InstanceID val="0">
     * <TransportState val="PLAYING"/>
     * <TransportStatus val="OK"/>
     * <CurrentTransportActions val="NEXT,PREV"/>
     * <AVTransportURI val="http://stream.example.com/radio.mp3"/>
     * <Volume channel="Master" val="17"/>
     * ...
     * </InstanceID>
     * </Event>
     *
     * We read child elements of <InstanceID ...> and store their attribute "val".
     * If you see "Volume channel=Master val=17", we might store "Volume"="17".
     *
     * @param lastChangeXml The raw XML from LastChange
     * @return Map of recognized fields => values (e.g. "TransportState" => "PLAYING", "Volume" => "17")
     */
    @Nullable
    public static Map<String, String> parseLastChange(String lastChangeXml) {
        if (lastChangeXml.isEmpty()) {
            return null;
        }
        LastChangeHandler handler = new LastChangeHandler();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

            SAXParser saxParser = factory.newSAXParser();
            saxParser.getXMLReader().setFeature("http://xml.org/sax/features/external-general-entities", false);
            saxParser.parse(new InputSource(new StringReader(lastChangeXml)), handler);
            return handler.getValues();
        } catch (IOException | SAXException | ParserConfigurationException e) {
            logger.debug("Error parsing LastChange XML: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------------
    // Internal Handlers
    // ------------------------------------------------------------------------

    /**
     * For DIDL-Lite track info fields (title, artist, album, albumArtUri).
     */
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
                    // ignore
                    break;
            }
            currentElement = "";
        }

        @Override
        @SuppressWarnings("null")
        public void characters(char @Nullable [] ch, int start, int length) throws SAXException {
            if (ch != null) {
                currentValue.append(ch, start, length);
            }
        }

        public Map<String, String> getValues() {
            return values.isEmpty() ? Collections.emptyMap() : values;
        }
    }

    /**
     * For AVTransport event fields (TransportState, CurrentTrackMetaData, etc.).
     */
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
                case "TransportState":
                case "CurrentTrackMetaData":
                case "CurrentTrackDuration":
                case "AVTransportURI":
                case "NextAVTransportURI":
                    addIfNotEmpty(values, currentElement, currentValue.toString());
                    break;
                default:
                    break;
            }
            currentElement = "";
        }

        @Override
        @SuppressWarnings("null")
        public void characters(char @Nullable [] ch, int start, int length) throws SAXException {
            if (ch != null) {
                currentValue.append(ch, start, length);
            }
        }

        public Map<String, String> getValues() {
            return values.isEmpty() ? Collections.emptyMap() : values;
        }
    }

    /**
     * For RenderingControl event fields (Volume, Mute, etc.).
     */
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
                    break;
            }
            currentElement = "";
        }

        @Override
        @SuppressWarnings("null")
        public void characters(char @Nullable [] ch, int start, int length) throws SAXException {
            if (ch != null) {
                currentValue.append(ch, start, length);
            }
        }

        public Map<String, String> getValues() {
            return values.isEmpty() ? Collections.emptyMap() : values;
        }
    }

    // ------------------------------------------------------------------------
    // NEW LastChangeHandler for typical "LastChange" event
    // ------------------------------------------------------------------------
    private static class LastChangeHandler extends DefaultHandler {

        private final Map<String, String> values = new HashMap<>();
        // private String currentElement = ""; // Removed unused field

        @Override
        public void startElement(@Nullable String uri, @Nullable String localName, @Nullable String qName,
                @Nullable Attributes attributes) {
            if (qName == null) {
                return;
            }
            // currentElement = qName; // Removed unused assignment

            // Typically we look for <InstanceID ...> child elements like <TransportState val="PLAYING"/>
            if (attributes != null && attributes.getValue("val") != null) {
                String val = attributes.getValue("val").trim();
                if (!val.isEmpty()) {
                    // Example: qName=TransportState, val=PLAYING
                    values.put(qName, val);
                }
            }
        }

        @Override
        public void endElement(@Nullable String uri, @Nullable String localName, @Nullable String qName) {
            // currentElement = ""; // Removed unused assignment
        }

        public Map<String, String> getValues() {
            return values.isEmpty() ? Collections.emptyMap() : values;
        }
    }

    // ------------------------------------------------------------------------
    // Helper for storing trimmed text into a map if non-empty
    // ------------------------------------------------------------------------
    private static void addIfNotEmpty(Map<String, String> map, String key, String value) {
        String trimmed = value.trim();
        if (!trimmed.isEmpty()) {
            map.put(key, trimmed);
        }
    }
}
