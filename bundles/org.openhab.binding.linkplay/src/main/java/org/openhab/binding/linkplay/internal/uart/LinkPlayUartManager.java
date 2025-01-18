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
package org.openhab.binding.linkplay.internal.uart;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.handler.LinkPlayDeviceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LinkPlayUartManager} handles UART communication over TCP socket with LinkPlay devices
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayUartManager {
    private static final int UART_PORT = 48308;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 1000;

    // UART Command Constants - keep these private to the UART manager
    private static final String CMD_PLAYBACK_STATUS = "MCU+PLA"; // Get playback status
    private static final String CMD_VOLUME = "MCU+VOL"; // Get/set volume
    private static final String CMD_MUTE = "MCU+MUT"; // Get/set mute status
    private static final String CMD_MULTIROOM_MODE = "MCU+MRM"; // Get multiroom mode
    private static final String CMD_CHANNEL_STATUS = "MCU+CHN"; // Get channel status

    private final Logger logger = LoggerFactory.getLogger(LinkPlayUartManager.class);
    private final String deviceId;
    private final String host;
    private final LinkPlayDeviceManager deviceManager;

    @Nullable
    private Socket socket;
    @Nullable
    private PrintWriter writer;
    @Nullable
    private BufferedReader reader;
    private boolean isConnected = false;

    public LinkPlayUartManager(String host, String deviceId, LinkPlayDeviceManager deviceManager) {
        this.host = host;
        this.deviceId = deviceId;
        this.deviceManager = deviceManager;

        logger.debug("[{}] Initializing UART connection and querying state", deviceId);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                connectSocket();
                isConnected = true;
                logger.debug("[{}] UART connection established on attempt {}", deviceId, attempt);
                return;
            } catch (IOException e) {
                if (attempt == MAX_RETRIES) {
                    logger.debug("[{}] UART connection test failed: {}. UART features will be disabled.", deviceId,
                            e.getMessage());
                } else {
                    logger.debug("[{}] UART connection attempt {} failed, retrying in {} ms", deviceId, attempt,
                            RETRY_DELAY_MS);
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    /**
     * Send UART command with proper framing
     */
    public synchronized @Nullable String sendCommand(String command) {
        if (!isConnected) {
            logger.debug("[{}] Cannot send command - UART not connected", deviceId);
            return null;
        }

        try {
            // Format command with proper framing
            String framedCommand = formatUartCommand(command);

            // Send command
            writer.write(framedCommand);
            writer.flush();

            // Read response
            String response = reader.readLine();
            if (response != null) {
                return parseResponse(response);
            }
        } catch (IOException e) {
            logger.debug("[{}] Error sending UART command: {}", deviceId, e.getMessage());
            disconnect();
        }
        return null;
    }

    /**
     * Format raw command with UART protocol framing
     */
    private String formatUartCommand(String command) {
        // Add protocol framing: 18 96 18 20 [LEN] 00 00 00 c1 02 00 00 00 00 00 00 00 00 00 00 [CMD]
        String len = String.format("%02x", command.length());
        String cmdHex = bytesToHex(command.getBytes());
        return String.format("18 96 18 20 %s 00 00 00 c1 02 00 00 00 00 00 00 00 00 00 00 %s", len, cmdHex);
    }

    private synchronized void connectSocket() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, UART_PORT), CONNECT_TIMEOUT_MS);
        writer = new PrintWriter(socket.getOutputStream(), true);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
    }

    public synchronized void disconnect() {
        isConnected = false;

        if (writer != null) {
            writer.close();
            writer = null;
        }

        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                logger.debug("[{}] Error closing reader: {}", deviceId, e.getMessage());
            }
            reader = null;
        }

        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                logger.debug("[{}] Error closing socket: {}", deviceId, e.getMessage());
            }
            socket = null;
        }
    }

    private void queryPlaybackStatus() {
        String response = sendCommand(CMD_PLAYBACK_STATUS);
        if (response != null && response.startsWith("PLA:")) {
            try {
                int value = Integer.parseInt(response.substring(4).trim());
                deviceManager.updateChannelFromUart("control", value);
            } catch (NumberFormatException e) {
                logger.debug("[{}] Invalid playback status response: {}", deviceId, response);
            }
        }
    }

    private void queryVolumeStatus() {
        String response = sendCommand(CMD_VOLUME);
        if (response != null && response.startsWith("VOL:")) {
            try {
                int value = Integer.parseInt(response.substring(4).trim());
                deviceManager.updateChannelFromUart("volume", value);
            } catch (NumberFormatException e) {
                logger.debug("[{}] Invalid volume response: {}", deviceId, response);
            }
        }
    }

    private void queryMuteStatus() {
        String response = sendCommand(CMD_MUTE);
        if (response != null && response.startsWith("MUT:")) {
            try {
                int value = Integer.parseInt(response.substring(4).trim());
                deviceManager.updateChannelFromUart("mute", value);
            } catch (NumberFormatException e) {
                logger.debug("[{}] Invalid mute response: {}", deviceId, response);
            }
        }
    }

    private void queryMultiroomStatus() {
        // Query multiroom mode
        String mrmResponse = sendCommand(CMD_MULTIROOM_MODE);
        if (mrmResponse != null && mrmResponse.startsWith("MRM:")) {
            String mode = mrmResponse.substring(4).trim();
            logger.debug("[{}] Multiroom mode: {}", deviceId, mode);
            // Store mode for future reference if needed
        }

        // Query channel status
        String chnResponse = sendCommand(CMD_CHANNEL_STATUS);
        if (chnResponse != null && chnResponse.startsWith("CHN:")) {
            String channel = chnResponse.substring(4).trim();
            logger.debug("[{}] Channel status: {}", deviceId, channel);
            // Store channel info for future reference if needed
        }
    }

    /**
     * Dispose of UART manager resources
     */
    public void dispose() {
        logger.debug("[{}] Disposing UART manager", deviceId);
        isConnected = false;
    }

    /**
     * Parse response to extract data after AXX or MCU marker
     */
    private String parseResponse(String response) {
        int pos = response.indexOf("AXX");
        if (pos == -1) {
            pos = response.indexOf("MCU");
        }
        if (pos >= 0) {
            return response.substring(pos, response.length() - 2);
        }
        return response;
    }

    /**
     * Convert bytes to hex string with spaces
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x ", b));
        }
        return hex.toString().trim();
    }

    /**
     * Convert space-separated hex string to byte array
     */
    private static byte[] hexStringToByteArray(String hex) {
        String[] bytes = hex.split(" ");
        byte[] result = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            result[i] = (byte) Integer.parseInt(bytes[i], 16);
        }
        return result;
    }
}
