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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * The {@link PlayerStatus} represents the current status of a LinkPlay device
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class PlayerStatus {
    private int volume;
    private boolean mute;
    private @Nullable String title;
    private @Nullable String artist;
    private @Nullable String album;
    private int duration;
    private int position;
    private String playStatus = "stop"; // play, pause, stop

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

    public @Nullable String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public @Nullable String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public @Nullable String getAlbum() {
        return album;
    }

    public void setAlbum(String album) {
        this.album = album;
    }

    public int getDuration() {
        return duration;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public String getPlayStatus() {
        return playStatus;
    }

    public void setPlayStatus(String playStatus) {
        this.playStatus = playStatus;
    }

    public static class Builder {
        private final PlayerStatus status;

        public Builder() {
            status = new PlayerStatus();
        }

        public Builder withVolume(int volume) {
            status.volume = volume;
            return this;
        }

        public Builder withMute(boolean mute) {
            status.mute = mute;
            return this;
        }

        public Builder withPlayStatus(String playStatus) {
            status.playStatus = playStatus;
            return this;
        }

        public Builder withTitle(@Nullable String title) {
            status.title = title;
            return this;
        }

        public Builder withArtist(@Nullable String artist) {
            status.artist = artist;
            return this;
        }

        public Builder withAlbum(@Nullable String album) {
            status.album = album;
            return this;
        }

        public Builder withDuration(int duration) {
            status.duration = duration;
            return this;
        }

        public Builder withPosition(int position) {
            status.position = position;
            return this;
        }

        public PlayerStatus build() {
            return status;
        }
    }
}
