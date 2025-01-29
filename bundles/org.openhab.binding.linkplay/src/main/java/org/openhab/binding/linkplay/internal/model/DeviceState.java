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
package org.openhab.binding.linkplay.internal.model;

import static org.openhab.binding.linkplay.internal.BindingConstants.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;

/**
 * Model class representing the complete state of a LinkPlay audio device.
 * This class maintains all device state information including:
 * - System information (device name, UDN)
 * - Network status (IP address, WiFi signal)
 * - Playback state (track info, volume, playback controls)
 * - Device configuration (MAC address, firmware)
 * 
 * The state is updated through the LinkPlayHttpManager and consumed by the LinkPlayThingHandler
 * to update OpenHAB channels. All state changes are tracked to minimize unnecessary updates.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class DeviceState {

    // System identification and configuration
    // UDN (Unique Device Name) - Used for UPnP device identification
    private @Nullable String deviceUDN;
    // User-configured or discovered device name
    private @Nullable String deviceName;

    // Network status and connectivity information
    // IP address is required for device communication
    private @Nullable String ipAddress;
    // WiFi signal strength in dBm, used to calculate signal quality percentage
    private int wifiSignalDbm;

    // Current media playback metadata
    // Track information is updated via HTTP API polling
    private @Nullable String trackTitle;
    private @Nullable String trackArtist;
    private @Nullable String trackAlbum;
    // URL to album artwork, may be null if not available
    private @Nullable String albumArtUrl;

    // Playback control state
    // Volume range: 0-100
    private int volume;
    // Mute, repeat, and shuffle are boolean toggles
    private boolean mute;
    private boolean repeat;
    private boolean loopOnce;
    private boolean shuffle;
    // Current audio source (e.g., Spotify, AirPlay, Line-in)
    private @Nullable String source;
    // Playback control state (play/pause/stop) from BindingConstants
    private String control = CONTROL_PAUSE;

    // Device hardware information
    // MAC address used for device identification
    private @Nullable String deviceMac;
    // Firmware version for feature compatibility checking
    private @Nullable String firmware;

    // Input and operation mode state
    // Current input source from BindingConstants.INPUT_*
    private String inputSource = INPUT_UNKNOWN;
    // Current operation mode from BindingConstants.MODE_*
    private String mode = MODE_UNKNOWN;

    public DeviceState() {
        // Initialize with defaults
        this.mode = MODE_UNKNOWN;
        this.inputSource = INPUT_UNKNOWN;
        this.control = CONTROL_PAUSE;
        // Initialize default values in constructor
        this.trackTitle = "";
        this.trackArtist = "";
        this.trackAlbum = "";
        this.albumArtUrl = null;
        this.volume = 0;
        this.mute = false;
        this.repeat = false;
        this.loopOnce = false;
        this.shuffle = false;
        this.source = SOURCE_UNKNOWN;
        this.deviceMac = null;
        this.firmware = null;
        this.wifiSignalDbm = 0;
        this.deviceUDN = null;
        this.deviceName = null;
    }

    /**
     * Initializes device state from the binding configuration.
     * Required fields like IP address are set from the config, along with
     * optional identification parameters (UDN, device name).
     *
     * @param config The binding configuration containing device parameters
     */
    public void initializeFromConfig(LinkPlayConfiguration config) {
        // IP address is required and must come from config
        this.ipAddress = config.getIpAddress();

        // Optional config values
        this.deviceUDN = config.getUdn();
        this.deviceName = config.getDeviceName();
    }

    // ----------------------
    // System identification getters/setters
    // ----------------------

    public @Nullable String getDeviceUDN() {
        return deviceUDN;
    }

    public void setDeviceUDN(String deviceUDN) {
        this.deviceUDN = deviceUDN;
    }

    public @Nullable String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    // ----------------------
    // Network status getters/setters
    // ----------------------

    public @Nullable String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(@Nullable String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public int getWifiSignalDbm() {
        return wifiSignalDbm;
    }

    public void setWifiSignalDbm(int wifiSignalDbm) {
        this.wifiSignalDbm = wifiSignalDbm;
    }

    /**
     * Converts the raw WiFi RSSI value in dBm to a percentage (0-100).
     * The conversion assumes a typical WiFi signal range:
     * - -100 dBm or lower = 0% signal strength
     * - -50 dBm or higher = 100% signal strength
     * - Linear scaling between these values
     *
     * @return WiFi signal strength as a percentage between 0 and 100
     */
    public int getWifiSignalStrength() {
        // Convert RSSI to percentage (typical range: -100 dBm to -50 dBm)
        if (wifiSignalDbm <= -100) {
            return 0;
        } else if (wifiSignalDbm >= -50) {
            return 100;
        } else {
            return 2 * (wifiSignalDbm + 100); // Linear conversion from -100..-50 to 0..100
        }
    }

    // ----------------------
    // Media playback metadata getters/setters
    // ----------------------

    @Nullable
    public String getTrackTitle() {
        return trackTitle;
    }

    public void setTrackTitle(String title) {
        if (!title.equals(this.trackTitle)) {
            this.trackTitle = title;
        }
    }

    @Nullable
    public String getTrackArtist() {
        return trackArtist;
    }

    public void setTrackArtist(String artist) {
        if (!artist.equals(this.trackArtist)) {
            this.trackArtist = artist;
        }
    }

    @Nullable
    public String getTrackAlbum() {
        return trackAlbum;
    }

    public void setTrackAlbum(@Nullable String trackAlbum) {
        this.trackAlbum = trackAlbum != null ? trackAlbum : "";
    }

    @Nullable
    public String getAlbumArtUrl() {
        return albumArtUrl;
    }

    public void setAlbumArtUrl(@Nullable String albumArtUrl) {
        this.albumArtUrl = albumArtUrl;
    }

    // ----------------------
    // Playback control getters/setters
    // ----------------------

    public int getVolume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = volume;
    }

    public boolean isMute() {
        return mute;
    }

    public void setMute(boolean mute) {
        this.mute = mute;
    }

    public boolean isRepeat() {
        return repeat;
    }

    public void setRepeat(boolean repeat) {
        this.repeat = repeat;
    }

    public boolean isLoopOnce() {
        return loopOnce;
    }

    public void setLoopOnce(boolean loopOnce) {
        this.loopOnce = loopOnce;
    }

    public boolean isShuffle() {
        return shuffle;
    }

    public void setShuffle(boolean shuffle) {
        this.shuffle = shuffle;
    }

    @Nullable
    public String getSource() {
        return source;
    }

    public void setSource(@Nullable String source) {
        this.inputSource = source != null ? source : INPUT_UNKNOWN;
    }

    public String getControl() {
        return control;
    }

    public void setControl(String control) {
        this.control = control;
    }

    // ----------------------
    // Device hardware information getters/setters
    // ----------------------

    public @Nullable String getDeviceMac() {
        return deviceMac;
    }

    public void setDeviceMac(@Nullable String deviceMac) {
        this.deviceMac = deviceMac;
    }

    public @Nullable String getFirmware() {
        return firmware;
    }

    public void setFirmware(String firmware) {
        this.firmware = firmware;
    }

    // ----------------------
    // Input and operation mode getters/setters
    // ----------------------

    public String getInputSource() {
        return inputSource;
    }

    public void setInputSource(String inputSource) {
        this.inputSource = inputSource;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }
}
