package org.openhab.binding.linkplay.internal.uart;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Low-level “Rakoit UART” TCP client for LinkPlay’s port 8899.
 */
public class LinkPlayUartClient implements Runnable {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayUartClient.class);

    private final String host;
    private final int port = 8899; // LinkPlay’s UART port

    private Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;

    private Thread readThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // A listener/callback interface to notify higher layers about unsolicited messages
    private final UartResponseListener listener;

    public LinkPlayUartClient(String host, UartResponseListener listener) {
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

        readThread = new Thread(this, "LinkPlayUartReadLoop-" + host);
        readThread.setDaemon(true);
        readThread.start();
        logger.debug("UART connection opened to {}:{}", host, port);
    }

    /**
     * The main read loop—continuously read any lines (or raw frames) from the device.
     */
    @Override
    public void run() {
        byte[] buffer = new byte[1024];
        try {
            while (running.get()) {
                int read = inputStream.read(buffer);
                if (read < 0) {
                    throw new IOException("LinkPlay UART socket closed");
                }
                // Some firmwares push ASCII lines like “AXX+VER:4.2.9326”, or “MCU+PAS+VOL:30&”.
                String response = new String(buffer, 0, read, StandardCharsets.ISO_8859_1);
                logger.trace("UART raw response: {}", response);

                // Pass it to your manager via a listener callback
                listener.onUartResponse(response);
            }
        } catch (IOException e) {
            logger.debug("UART read loop ended for {}:{}", host, port);
        } finally {
            close();
        }
    }

    /**
     * Send a command with the LinkPlay Rakoit packet structure:
     *   18 96 18 20 [len hex] 00 00 00 c1 02 00 00 00 00 00 00 00 00 00 ...
     */
    public synchronized void sendCommand(String cmd) throws IOException {
        if (!running.get() || outputStream == null) {
            throw new IOException("UART connection not open.");
        }

        // Convert length to 2-digit hex
        int length = cmd.length();
        String lengthHex = String.format("%02x", length);

        // Build the prefix + length + suffix (16 bytes) + ASCII cmd (hex)
        // Example: “18 96 18 20 0c 00 00 00 c1 02 ... [cmd chars in hex]”
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
        outputStream.write(bytes);
        outputStream.flush();

        logger.debug("UART sent command='{}' -> hex={}", cmd, finalHex);
    }

    /**
     * Convert hex string to byte array
     */
    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i),16) << 4)
                               + Character.digit(s.charAt(i+1),16));
        }
        return data;
    }

    public synchronized void close() {
        running.set(false);
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        socket = null;
        inputStream = null;
        outputStream = null;
        logger.debug("UART connection closed for {}:{}", host, port);
    }

    /**
     * Callback interface for real-time data:
     */
    public interface UartResponseListener {
        void onUartResponse(String rawText);
    }
}
