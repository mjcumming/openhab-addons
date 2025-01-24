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

import static org.openhab.binding.linkplay.internal.LinkPlayBindingConstants.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.config.LinkPlayConfiguration;

/**
 * Model class for device state information
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayDeviceState {

    // System info
    private @Nullable String deviceUDN;
    private @Nullable String deviceName;

    // Network info
    private @Nullable String ipAddress;
    private int wifiSignalDbm = 0;

    // Playback state
    private @Nullable String trackTitle = "";
    private @Nullable String trackArtist = "";
    private @Nullable String trackAlbum = "";
    private @Nullable String albumArtUrl;
    private int volume;
    private boolean mute;
    private boolean repeat = false;
    private boolean shuffle = false;
    private @Nullable String source = SOURCE_UNKNOWN;
    private @Nullable String control = CONTROL_PAUSE;

    // Device info
    private @Nullable String deviceMac;
    private @Nullable String firmware;

    // Basic audio control getters/setters
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

    @Nullable
    public String getControl() {
        return control;
    }

    public void setControl(String control) {
        this.control = control;
    }

    // Track metadata getters/setters
    @Nullable
    public String getTrackTitle() {
        return trackTitle;
    }

    @Nullable
    public String getTrackArtist() {
        return trackArtist;
    }

    @Nullable
    public String getTrackAlbum() {
        return trackAlbum;
    }

    @Nullable
    public String getAlbumArtUrl() {
        return albumArtUrl;
    }

    public void setAlbumArtUrl(@Nullable String albumArtUrl) {
        this.albumArtUrl = albumArtUrl;
    }

    // Device network information getters/setters
    @Nullable
    public String getSource() {
        return source;
    }

    public @Nullable String getDeviceMac() {
        return deviceMac;
    }

    public void setDeviceMac(@Nullable String deviceMac) {
        this.deviceMac = deviceMac;
    }

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

    // Add getters/setters for new fields
    public boolean isShuffle() {
        return shuffle;
    }

    public boolean isRepeat() {
        return repeat;
    }

    public @Nullable String getFirmware() {
        return firmware;
    }

    public void setFirmware(String firmware) {
        this.firmware = firmware;
    }

    public @Nullable String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public @Nullable String getDeviceUDN() {
        return deviceUDN;
    }

    public void setDeviceUDN(String deviceUDN) {
        this.deviceUDN = deviceUDN;
    }

    /**
     * Updates device state from the provided configuration
     * 
     * @param config The LinkPlay configuration to initialize from
     */
    public void initializeFromConfig(LinkPlayConfiguration config) {
        // Update IP address if provided
        String configIp = config.getIpAddress();
        if (!configIp.isEmpty()) {
            this.ipAddress = configIp;
        }

        // Update UDN if provided
        String configUdn = config.getUdn();
        if (!configUdn.isEmpty()) {
            this.deviceUDN = configUdn;
        }

        // Update device name if provided
        String configDeviceName = config.getDeviceName();
        if (!configDeviceName.isEmpty()) {
            this.deviceName = configDeviceName;
        }
    }

    // Update setters
    public void setTrackTitle(@Nullable String trackTitle) {
        this.trackTitle = trackTitle != null ? trackTitle : "";
    }

    public void setTrackArtist(@Nullable String trackArtist) {
        this.trackArtist = trackArtist != null ? trackArtist : "";
    }

    public void setTrackAlbum(@Nullable String trackAlbum) {
        this.trackAlbum = trackAlbum != null ? trackAlbum : "";
    }

    public void setSource(@Nullable String source) {
        this.source = source != null ? source : SOURCE_UNKNOWN;
    }

    public void setShuffle(boolean shuffle) {
        this.shuffle = shuffle;
    }

    public void setRepeat(boolean repeat) {
        this.repeat = repeat;
    }
}
