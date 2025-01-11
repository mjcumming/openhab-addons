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
package org.openhab.binding.linkplay.internal.upnp;

import static org.openhab.binding.linkplay.internal.LinkPlayBindingConstants.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PlayPauseType;
import org.openhab.core.thing.Thing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles UPnP AVTransport service events and state updates.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class AVTransportService {
    private final Logger logger = LoggerFactory.getLogger(AVTransportService.class);
    private final Thing thing;

    public AVTransportService(Thing thing) {
        this.thing = thing;
    }

    public void processStateVariable(String variable, String value) {
        logger.debug("Processing AVTransport state variable: {} = {}", variable, value);

        switch (variable) {
            case "TransportState":
                updateTransportState(value);
                break;
            case "CurrentTrackMetaData":
                updateTrackMetadata(value);
                break;
            case "CurrentPlayMode":
                updatePlayMode(value);
                break;
            default:
                logger.trace("Unhandled AVTransport variable: {}", variable);
        }
    }

    private void updateTransportState(String state) {
        try {
            switch (state.toUpperCase()) {
                case "PLAYING":
                    notifyListener("TransportState", PlayPauseType.PLAY.toString());
                    break;
                case "PAUSED_PLAYBACK":
                case "STOPPED":
                    notifyListener("TransportState", PlayPauseType.PAUSE.toString());
                    break;
                default:
                    logger.debug("Unknown transport state: {}", state);
            }
        } catch (Exception e) {
            logger.warn("Error updating transport state: {}", e.getMessage());
        }
    }

    private void updateTrackMetadata(String metadata) {
        try {
            DIDLParser.MetaData parsedMetadata = DIDLParser.parseMetadata(metadata);

            notifyListener("Title", parsedMetadata.title);
            notifyListener("Artist", parsedMetadata.artist);
            notifyListener("Album", parsedMetadata.album);

            if (!parsedMetadata.duration.isEmpty()) {
                notifyListener("Duration", parseDuration(parsedMetadata.duration));
            }
        } catch (Exception e) {
            logger.warn("Error updating track metadata: {}", e.getMessage());
        }
    }

    private String parseDuration(String duration) {
        try {
            String[] parts = duration.split(":");
            int seconds = 0;
            if (parts.length == 3) {
                seconds = Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60
                        + Integer.parseInt(parts[2]);
            } else if (parts.length == 2) {
                seconds = Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            }
            return String.valueOf(seconds);
        } catch (NumberFormatException e) {
            logger.warn("Invalid duration format: {}", duration);
            return "0";
        }
    }

    private void updatePlayMode(String mode) {
        try {
            switch (mode.toUpperCase()) {
                case "NORMAL":
                    notifyListener("Repeat", OnOffType.OFF.toString());
                    notifyListener("Shuffle", OnOffType.OFF.toString());
                    break;
                case "REPEAT_ALL":
                    notifyListener("Repeat", OnOffType.ON.toString());
                    notifyListener("Shuffle", OnOffType.OFF.toString());
                    break;
                case "SHUFFLE":
                    notifyListener("Shuffle", OnOffType.ON.toString());
                    break;
                default:
                    logger.debug("Unknown play mode: {}", mode);
            }
        } catch (Exception e) {
            logger.warn("Error updating play mode: {}", e.getMessage());
        }
    }

    private void notifyListener(String channelId, String value) {
        if (thing.getHandler() instanceof LinkPlayEventListener listener) {
            listener.onEventReceived(UPNP_SERVICE_TYPE_AV_TRANSPORT, channelId, value);
        }
    }

    public void synchronizeState() {
        try {
            if (thing.getHandler() instanceof LinkPlayEventListener listener) {
                listener.onEventReceived(UPNP_SERVICE_TYPE_AV_TRANSPORT, "GetTransportState", "");
                listener.onEventReceived(UPNP_SERVICE_TYPE_AV_TRANSPORT, "GetCurrentTrackMetaData", "");
                listener.onEventReceived(UPNP_SERVICE_TYPE_AV_TRANSPORT, "GetCurrentPlayMode", "");
            }
        } catch (Exception e) {
            logger.warn("Error synchronizing AVTransport state: {}", e.getMessage());
        }
    }
}
