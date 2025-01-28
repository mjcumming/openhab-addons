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
package org.openhab.binding.linkplay.internal.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.core.io.net.http.HttpClientFactory;

/**
 * Tests for {@link LinkPlayHttpClient}
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
public class LinkPlayHttpClientTest {

    private @Mock HttpClientFactory httpClientFactory;
    private @Mock HttpClient httpClient;
    private @Mock Request request;
    private @Mock ContentResponse response;

    private LinkPlayHttpClient linkPlayHttpClient;

    @BeforeEach
    public void setup() throws Exception {
        when(httpClientFactory.getCommonHttpClient()).thenReturn(httpClient);
        when(httpClient.newRequest(anyString())).thenReturn(request);
        when(request.send()).thenReturn(response);
        when(request.timeout(anyLong(), any())).thenReturn(request);
        when(request.method(anyString())).thenReturn(request);

        linkPlayHttpClient = new LinkPlayHttpClient(httpClientFactory);
    }

    @Test
    public void testGetPlayerStatus() throws Exception {
        // Setup
        String expectedResponse = "{\"status\":\"play\",\"vol\":\"37\",\"mute\":\"0\"}";
        when(response.getContentAsString()).thenReturn(expectedResponse);
        when(response.getStatus()).thenReturn(200);

        // Execute
        CompletableFuture<String> future = linkPlayHttpClient.getPlayerStatus("192.168.1.100");
        String result = future.get();

        // Verify
        assertEquals(expectedResponse, result);
        verify(httpClient).newRequest(contains("/httpapi.asp?command=getPlayerStatus"));
    }

    @Test
    public void testHttpError() {
        // Setup
        when(response.getStatus()).thenReturn(404);
        when(response.getContentAsString()).thenReturn("Not Found");

        // Execute and verify
        CompletableFuture<String> future = linkPlayHttpClient.getPlayerStatus("192.168.1.100");
        ExecutionException thrown = assertThrows(ExecutionException.class, future::get);
        assertTrue(thrown.getCause() instanceof LinkPlayCommunicationException);
    }

    @Test
    public void testInvalidIpAddress() {
        assertThrows(IllegalArgumentException.class, () -> linkPlayHttpClient.getPlayerStatus(""));
    }
}
