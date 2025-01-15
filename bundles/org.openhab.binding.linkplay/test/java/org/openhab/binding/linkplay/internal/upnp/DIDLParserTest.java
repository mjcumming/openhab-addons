package org.openhab.binding.linkplay.internal.upnp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DIDLParser}.
 * Demonstrates how to parse sample DIDL-Lite or empty/invalid metadata.
 */
public class DIDLParserTest {

    // A small DIDL snippet containing typical tags: dc:title, dc:creator, etc.
    private static final String SAMPLE_DIDL = 
        "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
        "           xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
        "  <item>" +
        "    <dc:title>My Song</dc:title>" +
        "    <dc:creator>The Artist</dc:creator>" +
        "    <upnp:album>The Album</upnp:album>" +
        "    <upnp:albumArtURI>http://example.com/cover.jpg</upnp:albumArtURI>" +
        "  </item>" +
        "</DIDL-Lite>";

    /**
     * Test parsing a well-formed DIDL snippet to ensure 
     * DIDLParser.parseMetadata(...) extracts the correct fields.
     */
    @Test
    public void testParseValidMetadata() {
        Map<String, String> result = DIDLParser.parseMetadata(SAMPLE_DIDL);
        assertNotNull(result, "Parser should return a non-null map for valid DIDL");

        // Check each key we expect
        assertEquals("My Song",     result.get("title"),       "Title mismatch");
        assertEquals("The Artist",  result.get("artist"),      "Artist mismatch");
        assertEquals("The Album",   result.get("album"),       "Album mismatch");
        assertEquals("http://example.com/cover.jpg", 
                     result.get("albumArtUri"), 
                     "albumArtUri mismatch");
    }

    /**
     * Test empty input (should return null based on the current DIDLParser logic).
     */
    @Test
    public void testParseEmptyMetadata() {
        Map<String, String> result = DIDLParser.parseMetadata("");
        assertNull(result, "Parser should return null for empty input");
    }

    /**
     * Test a malformed snippet to see that it doesn't throw unhandled exceptions.
     * The method should return null or an empty map (by design).
     */
    @Test
    public void testParseMalformedMetadata() {
        String malformed = "<DIDL-Lite><dc:title>Oops no closing tags!";
        Map<String, String> result = DIDLParser.parseMetadata(malformed);

        // Based on your current parser code, it returns null if parsing fails.
        assertNull(result, "Parser should return null for malformed DIDL");
    }
}
