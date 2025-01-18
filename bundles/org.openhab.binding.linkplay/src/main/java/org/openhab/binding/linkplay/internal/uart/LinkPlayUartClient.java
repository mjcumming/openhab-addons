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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.common.ThreadPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Low-level "Rakoit UART" TCP client for LinkPlay's port 8899.
 * Handles the socket connection and message formatting for the UART protocol.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayUartClient implements Runnable {

    private static final String THREAD_POOL_NAME = "linkplay-uart";

    private final Logger logger = LoggerFactory.getLogger(LinkPlayUartClient.class);

    private final String host;
    private final int port = 8899; // LinkPlay's UART port

    @Nullable
    private Socket socket;
    @Nullable
    private InputStream inputStream;
    @Nullable
    private OutputStream outputStream;

    @Nullable
    private ScheduledFuture<?> readFuture;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // A listener/callback interface to notify higher layers about unsolicited messages
    @NonNullByDefault
    private final LinkPlayUartResponseListener listener;

    public LinkPlayUartClient(String host, LinkPlayUartResponseListener listener) {
        this.host = host;
        this.listener = listener;
    }

    /**
     * Opens the socket, starts the read loop in a background thread.
     */
    public synchronized void open() throws IOException {
        if (running.get()) {
            return;
        }
        socket = new Socket(host, port);
        inputStream = socket.getInputStream();
        outputStream = socket.getOutputStream();
        running.set(true);

        // Use OpenHAB's thread pool instead of creating our own thread
        ScheduledExecutorService scheduler = ThreadPoolManager.getScheduledPool(THREAD_POOL_NAME);
        readFuture = scheduler.schedule(this, 0, TimeUnit.MILLISECONDS);

        logger.warn("UART connection opened to {}:{}", host, port);
    }

    /**
     * The main read loop—continuously read any lines (or raw frames) from the device.
     */
    @Override
    public void run() {
        InputStream localInputStream = inputStream;
        if (localInputStream == null) {
            logger.warn("UART read loop aborted - input stream is null for {}:{}", host, port);
            return;
        }

        byte[] buffer = new byte[1024];
        try {
            while (running.get()) {
                int read = localInputStream.read(buffer);
                if (read < 0) {
                    throw new IOException("LinkPlay UART socket closed");
                }
                // Some firmwares push ASCII lines like "AXX+VER:4.2.9326", or "MCU+PAS+VOL:30&"
                String response = new String(buffer, 0, read, StandardCharsets.ISO_8859_1);
                logger.warn("UART raw response: {}", response);

                // Pass it to your manager via a listener callback
                listener.onUartResponse(response);
            }
        } catch (IOException e) {
            logger.warn("UART read loop ended for {}:{}: {}", host, port, e.getMessage());
        } finally {
            close();
        }
    }

    /**
     * Send a command with the LinkPlay Rakoit packet structure:
     * 18 96 18 20 [len hex] 00 00 00 c1 02 00 00 00 00 00 00 00 00 00 ...
     */
    public synchronized void sendCommand(String cmd) throws IOException {
        OutputStream localOutputStream = outputStream;
        if (!running.get() || localOutputStream == null) {
            throw new IOException("UART connection not open to " + host + ":" + port);
        }

        // Convert length to 2-digit hex
        int length = cmd.length();
        String lengthHex = String.format("%02x", length);

        // Build the prefix + length + suffix (16 bytes) + ASCII cmd (hex)
        // Example: "18 96 18 20 0c 00 00 00 c1 02 ... [cmd chars in hex]"
        StringBuilder packetHex = new StringBuilder("18 96 18 20 ");
        packetHex.append(lengthHex).append(" ");
        packetHex.append("00 00 00 c1 02 00 00 00 00 00 00 00 00 00 00 ");

        // Convert each character of cmd to 2-digit hex
        for (char c : cmd.toCharArray()) {
            packetHex.append(String.format("%02x ", (int) c));
        }
        // Remove spaces to parse:
        String finalHex = packetHex.toString().trim().replace(" ", "");

        byte[] bytes = hexStringToByteArray(finalHex);
        localOutputStream.write(bytes);
        localOutputStream.flush();

        logger.warn("UART sent command='{}' -> hex={}", cmd, finalHex);
    }

    /**
     * Convert hex string to byte array
     */
    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    public synchronized void close() {
        running.set(false);

        // Cancel the read future first
        ScheduledFuture<?> localReadFuture = readFuture;
        if (localReadFuture != null) {
            localReadFuture.cancel(true);
            readFuture = null;
        }

        // Then close the streams and socket
        Socket localSocket = socket;
        InputStream localInputStream = inputStream;
        OutputStream localOutputStream = outputStream;

        if (localInputStream != null) {
            try {
                localInputStream.close();
            } catch (IOException e) {
                logger.debug("Error closing input stream for {}:{}: {}", host, port, e.getMessage());
            }
        }

        if (localOutputStream != null) {
            try {
                localOutputStream.close();
            } catch (IOException e) {
                logger.debug("Error closing output stream for {}:{}: {}", host, port, e.getMessage());
            }
        }

        if (localSocket != null) {
            try {
                localSocket.close();
            } catch (IOException e) {
                logger.debug("Error closing socket for {}:{}: {}", host, port, e.getMessage());
            }
        }

        socket = null;
        inputStream = null;
        outputStream = null;
        logger.debug("UART connection closed for {}:{}", host, port);
    }
}
