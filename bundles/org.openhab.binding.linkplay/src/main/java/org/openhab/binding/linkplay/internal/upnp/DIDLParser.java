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
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Parser for UPnP DIDL and LastChange XML content
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class DIDLParser {
    private static final Logger logger = LoggerFactory.getLogger(DIDLParser.class);

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

    public static MetaData parseMetadata(String metadata) {
        MetaData result = new MetaData();
        if (metadata == null || metadata.isEmpty()) {
            return result;
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(metadata)));

            XPath xpath = XPathFactory.newInstance().newXPath();

            // Extract standard DIDL-Lite fields
            result.title = getXPathValue(xpath, "//dc:title", doc);
            result.artist = getXPathValue(xpath, "//dc:creator", doc);
            result.album = getXPathValue(xpath, "//upnp:album", doc);
            result.artworkUrl = getXPathValue(xpath, "//upnp:albumArtURI", doc);

        } catch (Exception e) {
            logger.debug("Error parsing metadata XML: {}", e.getMessage());
        }

        return result;
    }

    public static Map<String, String> getAVTransportFromXML(String xml) {
        Map<String, String> result = new HashMap<>();
        if (xml == null || xml.isEmpty()) {
            return result;
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));

            XPath xpath = XPathFactory.newInstance().newXPath();

            // Get all InstanceID elements
            NodeList instances = (NodeList) xpath.evaluate("//InstanceID", doc, XPathConstants.NODESET);
            for (int i = 0; i < instances.getLength(); i++) {
                Node instance = instances.item(i);
                NodeList properties = instance.getChildNodes();

                // Process each property in the InstanceID
                for (int j = 0; j < properties.getLength(); j++) {
                    Node property = properties.item(j);
                    if (property.getNodeType() == Node.ELEMENT_NODE) {
                        String name = property.getNodeName();
                        String value = property.getAttributes().getNamedItem("val").getNodeValue();
                        result.put(name, value);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Error parsing AVTransport LastChange XML: {}", e.getMessage());
        }

        return result;
    }

    public static Map<String, String> getRenderingControlFromXML(String xml) {
        Map<String, String> result = new HashMap<>();
        if (xml == null || xml.isEmpty()) {
            return result;
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));

            XPath xpath = XPathFactory.newInstance().newXPath();

            // Get all InstanceID elements
            NodeList instances = (NodeList) xpath.evaluate("//InstanceID", doc, XPathConstants.NODESET);
            for (int i = 0; i < instances.getLength(); i++) {
                Node instance = instances.item(i);
                NodeList properties = instance.getChildNodes();

                // Process each property in the InstanceID
                for (int j = 0; j < properties.getLength(); j++) {
                    Node property = properties.item(j);
                    if (property.getNodeType() == Node.ELEMENT_NODE) {
                        String name = property.getNodeName();
                        String value = property.getAttributes().getNamedItem("val").getNodeValue();
                        result.put(name, value);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Error parsing RenderingControl LastChange XML: {}", e.getMessage());
        }

        return result;
    }

    private static String getXPathValue(XPath xpath, String expression, Document doc) {
        try {
            return (String) xpath.evaluate(expression, doc, XPathConstants.STRING);
        } catch (Exception e) {
            return "";
        }
    }
}
