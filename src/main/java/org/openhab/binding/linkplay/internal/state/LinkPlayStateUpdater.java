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
package org.openhab.binding.linkplay.internal.state;

import java.util.Map;

import org.openhab.binding.linkplay.internal.handler.LinkPlayHandler;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LinkPlayStateUpdater} updates the states of a Thing based on the parsed response.
 *
 * @author Michael Cumming
 */
public class LinkPlayStateUpdater {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlayStateUpdater.class);

    /**
     * Updates the Thing's state channels based on the provided values.
     *
     * @param thing The Thing to update.
     * @param values A map of channel IDs to values.
     */
    public void updateStates(Thing thing, Map<String, String> values) {
        values.forEach((channelId, value) -> {
            thing.getChannel(channelId).ifPresent(channel -> {
                State state = new StringType(value);
                if (thing.getHandler() instanceof LinkPlayHandler handler) {
                    handler.updateState(channel.getUID(), state);
                    logger.debug("Updated channel '{}' with value '{}'", channelId, value);
                }
            });
        });
    }

    /**
     * Updates the Thing's status.
     *
     * @param thing The Thing to update.
     * @param status The new status.
     * @param detail Additional details about the status.
     */
    public void updateStatus(Thing thing, ThingStatus status, ThingStatusDetail detail) {
        ThingStatusInfo statusInfo = new ThingStatusInfo(status, detail, null);
        thing.setStatusInfo(statusInfo);
        logger.debug("Updated Thing status to '{}' with detail '{}'", status, detail);
    }
}
