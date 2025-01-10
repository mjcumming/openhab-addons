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
package org.openhab.binding.linkplay.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link LinkPlayBindingConstants} class defines common constants used across the binding.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayBindingConstants {

    public static final String BINDING_ID = "linkplay";

    // Thing Types
    public static final ThingTypeUID THING_TYPE_DEVICE = new ThingTypeUID(BINDING_ID, "device");

    // Manufacturer
    public static final String SUPPORTED_MANUFACTURER = "LinkPlay";

    // List of all Channel IDs
    public static final String CHANNEL_CONTROL = "control";
    public static final String CHANNEL_REPEAT = "repeat";
    public static final String CHANNEL_SHUFFLE = "shuffle";
    public static final String CHANNEL_TITLE = "title";
    public static final String CHANNEL_ARTIST = "artist";
    public static final String CHANNEL_ALBUM = "album";
    public static final String CHANNEL_ALBUM_ART = "albumArt";
    public static final String CHANNEL_DURATION = "duration";
    public static final String CHANNEL_POSITION = "position";
    public static final String CHANNEL_SOURCE = "source";
    public static final String CHANNEL_VOLUME = "volume";
    public static final String CHANNEL_MUTE = "mute";
    public static final String CHANNEL_SLAVE_VOLUME = "slaveVolume";
    public static final String CHANNEL_SLAVE_MUTE = "slaveMute";
    public static final String CHANNEL_PSEUDO_MASTER_VOLUME = "pseudoMasterVolume";
    public static final String CHANNEL_PSEUDO_MASTER_MUTE = "pseudoMasterMute";
    public static final String CHANNEL_GROUP_ROLE = "groupRole";
    public static final String CHANNEL_GROUP_MASTER_IP = "groupMasterIP";
    public static final String CHANNEL_GROUP_SLAVE_IPS = "groupSlaveIPs";
    public static final String CHANNEL_GROUP_JOIN = "groupJoin";
    public static final String CHANNEL_GROUP_LEAVE = "groupLeave";
    public static final String CHANNEL_GROUP_UNGROUP = "groupUngroup";
    public static final String CHANNEL_GROUP_SLAVE_KICKOUT = "groupSlaveKickout";
    public static final String CHANNEL_GROUP_VOLUME = "groupVolume";
    public static final String CHANNEL_GROUP_MUTE = "groupMute";
    public static final String CHANNEL_IP_ADDRESS = "ipAddress";
    public static final String CHANNEL_MAC_ADDRESS = "macAddress";
    public static final String CHANNEL_WIFI_SIGNAL = "wifiSignal";

    // Configuration properties
    public static final String CONFIG_IP_ADDRESS = "ipAddress";
    public static final String CONFIG_DEVICE_ID = "deviceId";
    public static final String CONFIG_POLLING_INTERVAL = "pollingInterval";
    public static final String CONFIG_DEVICE_NAME = "deviceName";
    public static final int DEFAULT_POLLING_INTERVAL = 30; // seconds

    // UPnP related constants
    public static final String UPNP_DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";
    public static final String UPNP_SERVICE_TYPE_RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1";
    public static final String UPNP_SERVICE_TYPE_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    public static final String UPNP_SERVICE_TYPE_CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1";

    // Channel Group IDs
    public static final String CHANNEL_GROUP_PLAYBACK = "playback";
    public static final String CHANNEL_GROUP_CONTROL = "control";
    public static final String CHANNEL_GROUP_INFO = "info";
    public static final String CHANNEL_GROUP_MULTIROOM = "multiroom";

    // HTTP Related Constants
    public static final String API_URL_PREFIX = "http://%s:8080";
    public static final String API_ENDPOINT_STATUS = "/status";
    public static final String API_ENDPOINT_CONTROL = "/control";
    public static final int DEFAULT_HTTP_TIMEOUT = 5000; // milliseconds

    // UPnP Discovery Constants
    public static final String UPNP_DISCOVERY_THING_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";
    public static final String UPNP_MANUFACTURER = "LinkPlay";
    public static final String UPNP_DEVICE_TYPE_PREFIX = "urn:schemas-upnp-org:device:";
}
