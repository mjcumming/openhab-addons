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
package org.openhab.binding.linkplay.internal.handler;

import static org.openhab.binding.linkplay.internal.LinkPlayBindingConstants.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.linkplay.internal.http.LinkPlayHttpClient;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles multiroom group commands and state updates for LinkPlay devices.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayGroupManager {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayGroupManager.class);
    private final LinkPlayHttpClient httpClient;

    public LinkPlayGroupManager(LinkPlayHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Handles group-related commands like join, leave, and ungroup.
     *
     * @param channelUID The channel for the command.
     * @param command The command to execute.
     */
    public void handleGroupCommand(ChannelUID channelUID, Command command) {
        String action;
        String parameter = null;

        switch (channelUID.getId()) {
            case CHANNEL_GROUP_JOIN:
                if (command instanceof StringType) {
                    action = "multiroom:JoinGroup";
                    parameter = ((StringType) command).toString();
                } else {
                    logger.warn("Invalid command type for group join: {}", command.getClass().getName());
                    return;
                }
                break;

            case CHANNEL_GROUP_LEAVE:
                action = "multiroom:LeaveGroup";
                break;

            case CHANNEL_GROUP_UNGROUP:
                action = "multiroom:Ungroup";
                break;

            default:
                logger.warn("Unhandled group channel: {}", channelUID.getId());
                return;
        }

        executeGroupCommand(action, parameter);
    }

    /**
     * Executes the group command via the HTTP client.
     *
     * @param action The action to perform (e.g., join, leave, ungroup).
     * @param parameter Optional parameter for the action.
     */
    private void executeGroupCommand(String action, @Nullable String parameter) {
        String command = (parameter != null && !parameter.isEmpty()) ? action + ":" + parameter : action;
        httpClient.sendCommand(command);
    }

    /**
     * Periodically updates the group state of the device.
     *
     * @param thing The Thing to update.
     */
    public boolean updateGroupState(Thing thing) {
        try {
            httpClient.sendCommand("multiroom:getSlaveList").whenComplete((response, error) -> {
                if (error != null) {
                    logger.warn("Failed to fetch group state: {}", error.getMessage());
                    return;
                }

                logger.debug("Group state response: {}", response);
                // Parse and update group-related channels (e.g., group role, slave list).
                // TODO: Implement response parsing and state updates based on parsed data.
            });
            return true;
        } catch (Exception e) {
            logger.warn("Failed to fetch group state: {}", e.getMessage());
            return false;
        }
    }
}
