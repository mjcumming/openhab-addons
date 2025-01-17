package org.openhab.binding.linkplay.internal.uart;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.handler.LinkPlayDeviceManager;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LinkPlayUartManager handles all UART-based communication:
 * - Opens/closes the client socket (port 8899)
 * - Sends commands (volume, etc.)
 * - Listens for real-time data from the device,
 *   then calls deviceManager.updateChannelsFromUart(...) or similar.
 */
@NonNullByDefault
public class LinkPlayUartManager implements LinkPlayUartClient.UartResponseListener {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayUartManager.class);

    private final LinkPlayDeviceManager deviceManager;
    private final LinkPlayConfiguration config;

    private LinkPlayUartClient uartClient;
    private final String deviceId;

    private boolean running = false;

    public LinkPlayUartManager(LinkPlayDeviceManager deviceManager, LinkPlayConfiguration config, String deviceId) {
        this.deviceManager = deviceManager;
        this.config = config;
        this.deviceId = deviceId;
    }

    public void start() {
        if (running) {
            return;
        }
        String host = config.getIpAddress();
        uartClient = new LinkPlayUartClient(host, this);
        try {
            uartClient.open();
            running = true;
            logger.debug("[{}] LinkPlayUartManager started (host={})", deviceId, host);
        } catch (Exception e) {
            logger.warn("[{}] Failed to open UART connection => {}", deviceId, e.getMessage());
        }
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (uartClient != null) {
            uartClient.close();
        }
        logger.debug("[{}] LinkPlayUartManager stopped", deviceId);
    }

    /**
     * If a user toggles the volume or other command from openHAB UI, we format the Rakoit commands, e.g.:
     *  “MCU+PAS+VOL:30&”
     */
    public void sendVolume(int volume) {
        String cmd = "MCU+PAS+VOL:" + volume + "&";
        try {
            if (uartClient != null) {
                uartClient.sendCommand(cmd);
            }
        } catch (Exception e) {
            logger.warn("[{}] Failed to send volume cmd => {}", deviceId, e.getMessage());
        }
    }

    public void sendMute(boolean mute) {
        String cmd = "MCU+PAS+MUTE:" + (mute ? "1" : "0") + "&";
        // ...
    }

    // add more if needed: LED off, next track, etc.

    /**
     * The callback for any raw text from the device. Here we parse “AXX” or “MCU+...” lines.
     */
    @Override
    public void onUartResponse(String rawText) {
        logger.trace("[{}] onUartResponse => {}", deviceId, rawText);

        // Example parse: “MCU+PAS+VOL:30&”
        // Then deviceManager.updateChannel(“volume”, 30);
        if (rawText.contains("VOL:")) {
            int colonIndex = rawText.indexOf("VOL:");
            int ampIndex = rawText.indexOf("&", colonIndex);
            if (ampIndex < 0) ampIndex = rawText.length();
            String volPart = rawText.substring(colonIndex + 4, ampIndex).trim();
            try {
                int newVolume = Integer.parseInt(volPart);
                // We have the volume => pass to deviceManager
                deviceManager.updateChannelFromUart("volume", newVolume);
            } catch (NumberFormatException e) {
                logger.debug("[{}] Could not parse volume from response={}", deviceId, rawText);
            }
        }

        // Similarly handle other commands: “MUTE:1&”, “LED:0&”, “AXX+VER:4.2.9326”, etc.
        // Then call deviceManager to reflect changes in openHAB channels.
    }
}
