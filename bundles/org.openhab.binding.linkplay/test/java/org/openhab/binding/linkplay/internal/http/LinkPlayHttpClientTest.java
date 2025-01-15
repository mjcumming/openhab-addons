package org.openhab.binding.linkplay.internal.http;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openhab.core.io.net.http.HttpClientFactory;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Basic integration-like tests for {@link LinkPlayHttpClient} using WireMock
 * to simulate LinkPlay device responses. This is not a full integration test,
 * but demonstrates how to validate the HTTP logic.
 */
public class LinkPlayHttpClientTest {

    private static WireMockServer wireMockServer;

    // The class under test
    private static LinkPlayHttpClient linkPlayHttpClient;

    @BeforeAll
    public static void setup() throws Exception {
        // Start WireMock on a dynamic port
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();

        // Tell WireMock to expect requests on that port
        configureFor("localhost", wireMockServer.port());

        // We'll create a minimal HttpClientFactory that returns an openHAB commonHttpClient
        // or you might mock it. For illustration, let's assume we have a real factory:
        HttpClientFactory factory = new TestHttpClientFactory(); // Example stub or real factory
        linkPlayHttpClient = new LinkPlayHttpClient(factory) {
            @Override
            protected int[] getHttpsPorts() {
                // We override if we want to skip the real HTTPS attempt in the test
                // or if you'd prefer to test fallback. For simplicity, set empty so we always do HTTP
                return new int[] {};
            }
        };
    }

    @AfterAll
    public static void teardown() throws Exception {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    /**
     * Test a successful HTTP request to getPlayerStatus.
     * We'll stub the device to respond with a typical JSON body.
     */
    @Test
    public void testGetPlayerStatus_Success() throws InterruptedException, ExecutionException {
        // 1) Prepare a stubbed endpoint
        stubFor(get(urlMatching("/httpapi.asp\\?command=getPlayerStatus"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":\"play\",\"vol\":\"37\",\"mute\":\"0\"}")));

        // 2) Make the call
        String ipAddress = "localhost";
        // We'll pass the dynamic port from wireMockServer to ensure the code
        // hits that mock server. Typically the client tries port 443/4443 and then port 80,
        // so if you want to do this purely on HTTP, override the ports as shown in setup().
        CompletableFuture<String> futureResponse = linkPlayHttpClient.getPlayerStatus(ipAddress);
        String content = futureResponse.get(); // block for test

        // 3) Verify
        assertNotNull(content);
        assertTrue(content.contains("\"status\":\"play\""), "Expected 'play' in response");
        assertTrue(content.contains("\"vol\":\"37\""), "Expected 'vol' in response");
        assertTrue(content.contains("\"mute\":\"0\""), "Expected 'mute' in response");

        // 4) Check that the request was actually made
        verify(getRequestedFor(urlMatching("/httpapi.asp\\?command=getPlayerStatus")));
    }

    /**
     * Test the scenario where the device returns an error text in the response, 
     * which triggers a LinkPlayApiException in the client.
     */
    @Test
    public void testSendCommand_ApiError() throws InterruptedException {
        stubFor(get(urlMatching("/httpapi.asp\\?command=getPlayerStatus"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("{\"status\":\"error\",\"message\":\"Some device error\"}")));

        String ipAddress = "localhost";
        CompletableFuture<String> futureResponse = linkPlayHttpClient.getPlayerStatus(ipAddress);

        ExecutionException thrown = assertThrows(ExecutionException.class, futureResponse::get,
            "Expected an ExecutionException due to 'error' in the body");

        // The cause should be LinkPlayApiException
        assertTrue(thrown.getCause() instanceof LinkPlayApiException,
            "Cause should be LinkPlayApiException");
        assertTrue(thrown.getCause().getMessage().contains("Some device error"),
            "Exception message should contain device error info");
    }

    /**
     * Test we throw an IllegalArgumentException if IP is empty.
     * This checks the guard in {@code sendCommand}.
     */
    @Test
    public void testSendCommand_EmptyIp() {
        assertThrows(IllegalArgumentException.class, () -> linkPlayHttpClient.getPlayerStatus("").join(),
            "Empty IP should cause an IllegalArgumentException");
    }

    // Additional tests:
    // - test fallback from HTTPS to HTTP if you'd like to do real multiport tries
    // - test 'error' or 'fail' substring in body
    // - test timeouts, ...
}
