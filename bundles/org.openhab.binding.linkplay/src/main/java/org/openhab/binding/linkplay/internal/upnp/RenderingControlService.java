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
import org.openhab.core.library.types.PercentType;
import org.openhab.core.thing.Thing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles UPnP RenderingControl service events and state updates.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class RenderingControlService {
    private final Logger logger = LoggerFactory.getLogger(RenderingControlService.class);
    private final Thing thing;

    public RenderingControlService(Thing thing) {
        this.thing = thing;
    }

    public void processStateVariable(String variable, String value) {
        logger.debug("Processing RenderingControl state variable: {} = {}", variable, value);

        switch (variable) {
            case "Volume":
                updateVolume(value);
                break;
            case "Mute":
                updateMute(value);
                break;
            default:
                logger.trace("Unhandled RenderingControl variable: {}", variable);
        }
    }

    private void updateVolume(String value) {
        try {
            int volume = Integer.parseInt(value);
            if (volume >= 0 && volume <= 100) {
                notifyListener(CHANNEL_VOLUME, new PercentType(volume).toString());
            }
        } catch (NumberFormatException e) {
            logger.debug("Invalid volume value: {}", value);
        } catch (Exception e) {
            logger.warn("Error updating volume: {}", e.getMessage());
        }
    }

    private void updateMute(String value) {
        try {
            notifyListener(CHANNEL_MUTE, OnOffType.from("1".equals(value)).toString());
        } catch (Exception e) {
            logger.warn("Error updating mute state: {}", e.getMessage());
        }
    }

    private void notifyListener(String channelId, String value) {
        if (thing.getHandler() instanceof LinkPlayEventListener listener) {
            listener.onEventReceived(UPNP_SERVICE_TYPE_RENDERING_CONTROL, channelId, value);
        }
    }

    public void synchronizeState() {
        try {
            if (thing.getHandler() instanceof LinkPlayEventListener listener) {
                listener.onEventReceived(UPNP_SERVICE_TYPE_RENDERING_CONTROL, "GetVolume", "");
                listener.onEventReceived(UPNP_SERVICE_TYPE_RENDERING_CONTROL, "GetMute", "");
            }
        } catch (Exception e) {
            logger.warn("Error synchronizing RenderingControl state: {}", e.getMessage());
        }
    }
}
