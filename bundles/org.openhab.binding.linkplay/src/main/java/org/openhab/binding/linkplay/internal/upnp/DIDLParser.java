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
 * Parser for DIDL-Lite XML content used in UPnP events
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class DIDLParser {

    private static final Logger logger = LoggerFactory.getLogger(DIDLParser.class);

    @Nullable
    public static Map<String, String> parseMetadata(String metadata) {
        if (metadata.isEmpty()) {
            return null;
        }

        MetadataHandler handler = new MetadataHandler();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser saxParser = factory.newSAXParser();
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            saxParser.getXMLReader().setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            saxParser.parse(new InputSource(new StringReader(metadata)), handler);
            return handler.getValues();
        } catch (IOException | SAXException | ParserConfigurationException e) {
            logger.debug("Error parsing DIDL-Lite metadata: {}", e.getMessage());
            return null;
        }
    }

    @Nullable
    public static Map<String, String> getAVTransportFromXML(String xml) {
        if (xml.isEmpty()) {
            return null;
        }

        AVTransportHandler handler = new AVTransportHandler();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser saxParser = factory.newSAXParser();
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            saxParser.getXMLReader().setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            saxParser.parse(new InputSource(new StringReader(xml)), handler);
            return handler.getValues();
        } catch (IOException | SAXException | ParserConfigurationException e) {
            logger.debug("Error parsing AVTransport XML: {}", e.getMessage());
            return null;
        }
    }

    @Nullable
    public static Map<String, String> getRenderingControlFromXML(String xml) {
        if (xml.isEmpty()) {
            return null;
        }

        RenderingControlHandler handler = new RenderingControlHandler();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser saxParser = factory.newSAXParser();
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            saxParser.getXMLReader().setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            saxParser.parse(new InputSource(new StringReader(xml)), handler);
            return handler.getValues();
        } catch (IOException | SAXException | ParserConfigurationException e) {
            logger.debug("Error parsing RenderingControl XML: {}", e.getMessage());
            return null;
        }
    }

    private static class MetadataHandler extends DefaultHandler {
        private final Map<String, String> values = new HashMap<>();
        private StringBuilder currentValue = new StringBuilder();
        private String currentElement = "";

        @Override
        public void startElement(@Nullable String uri, @Nullable String localName, @Nullable String qName,
                @Nullable Attributes attributes) {
            if (qName == null) {
                return;
            }
            currentValue.setLength(0);
            currentElement = qName;

            // Get value from attribute if available
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
            }
            currentElement = "";
        }

        public Map<String, String> getValues() {
            return values.isEmpty() ? Collections.emptyMap() : values;
        }
    }

    private static class AVTransportHandler extends DefaultHandler {
        private final Map<String, String> values = new HashMap<>();
        private StringBuilder currentValue = new StringBuilder();
        private String currentElement = "";

        @Override
        public void startElement(@Nullable String uri, @Nullable String localName, @Nullable String qName,
                @Nullable Attributes attributes) {
            if (qName == null) {
                return;
            }
            currentValue.setLength(0);
            currentElement = qName;

            // Get value from attribute if available
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
            }
            currentElement = "";
        }

        public Map<String, String> getValues() {
            return values.isEmpty() ? Collections.emptyMap() : values;
        }
    }

    private static class RenderingControlHandler extends DefaultHandler {
        private final Map<String, String> values = new HashMap<>();
        private StringBuilder currentValue = new StringBuilder();
        private String currentElement = "";

        @Override
        public void startElement(@Nullable String uri, @Nullable String localName, @Nullable String qName,
                @Nullable Attributes attributes) {
            if (qName == null) {
                return;
            }
            currentValue.setLength(0);
            currentElement = qName;

            // Get value from attribute if available
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
            }
            currentElement = "";
        }

        public Map<String, String> getValues() {
            return values.isEmpty() ? Collections.emptyMap() : values;
        }
    }

    private static void addIfNotEmpty(Map<String, String> map, String key, String value) {
        if (!value.trim().isEmpty()) {
            map.put(key, value.trim());
        }
    }
}
