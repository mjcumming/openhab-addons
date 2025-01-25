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

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link BindingConstants} class defines common constants used across the binding.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class BindingConstants {

    public static final String BINDING_ID = "linkplay";

    // Thing Type UIDs - using mediastreamer
    public static final ThingTypeUID THING_TYPE_MEDIASTREAMER = new ThingTypeUID(BINDING_ID, "mediastreamer");
    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Collections.singleton(THING_TYPE_MEDIASTREAMER);

    // Thing Configuration parameters
    public static final String CONFIG_IP_ADDRESS = "ipAddress";
    public static final String CONFIG_UDN = "udn";
    public static final String CONFIG_PLAYER_STATUS_POLLING_INTERVAL = "playerStatusPollingInterval";
    public static final String CONFIG_DEVICE_STATUS_POLLING_INTERVAL = "deviceStatusPollingInterval";
    public static final String CONFIG_DEVICE_NAME = "deviceName";

    // Default polling intervals (in seconds)
    public static final int DEFAULT_PLAYER_STATUS_POLLING_INTERVAL = 5;
    public static final int DEFAULT_DEVICE_STATUS_POLLING_INTERVAL = 10;

    // Thing Properties
    public static final String PROPERTY_UDN = "udn";
    public static final String PROPERTY_FIRMWARE = "firmwareVersion";
    public static final String PROPERTY_MODEL = "model";
    public static final String PROPERTY_IP = "ipAddress";
    public static final String PROPERTY_MAC = "macAddress";
    public static final String PROPERTY_MANUFACTURER = "manufacturer";

    // Channel Groups
    public static final String GROUP_PLAYBACK = "playback";
    public static final String GROUP_SYSTEM = "system";
    public static final String GROUP_MULTIROOM = "multiroom";
    public static final String GROUP_NETWORK = "network";

    // Playback Channels
    public static final String CHANNEL_CONTROL = "control"; // PLAY/PAUSE commands
    public static final String CHANNEL_TITLE = "title"; // Track title
    public static final String CHANNEL_ARTIST = "artist"; // Track artist
    public static final String CHANNEL_ALBUM = "album"; // Track album
    public static final String CHANNEL_DURATION = "duration"; // Track duration (Number:Time)
    public static final String CHANNEL_POSITION = "position"; // Current track position (Number:Time)
    public static final String CHANNEL_VOLUME = "volume"; // Playback volume
    public static final String CHANNEL_MUTE = "mute"; // Mute status
    public static final String CHANNEL_REPEAT = "repeat"; // On/off repeat
    public static final String CHANNEL_SHUFFLE = "shuffle"; // On/off shuffle
    public static final String CHANNEL_SOURCE = "source"; // Audio source selection
    public static final String CHANNEL_ALBUM_ART = "albumArt";

    // System Channels
    public static final String CHANNEL_DEVICE_NAME = "deviceName";
    public static final String CHANNEL_FIRMWARE = "firmware";

    // Network Channels
    public static final String CHANNEL_MAC_ADDRESS = "macAddress";
    public static final String CHANNEL_WIFI_SIGNAL = "wifiSignal";

    // Multiroom Channels
    public static final String CHANNEL_ROLE = "role";
    public static final String CHANNEL_MASTER_IP = "masterIP";
    public static final String CHANNEL_SLAVE_IPS = "slaveIPs";
    public static final String CHANNEL_GROUP_NAME = "groupName";
    public static final String CHANNEL_JOIN = "join";
    public static final String CHANNEL_LEAVE = "leave";
    public static final String CHANNEL_UNGROUP = "ungroup";
    public static final String CHANNEL_KICKOUT = "kickout";
    public static final String CHANNEL_GROUP_VOLUME = "groupVolume";
    public static final String CHANNEL_GROUP_MUTE = "groupMute";

    // UPnP Discovery Constants
    public static final String UPNP_DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";
    public static final String UPNP_SERVICE_TYPE_RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1";
    public static final String UPNP_SERVICE_TYPE_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    public static final String UPNP_SERVICE_TYPE_CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1";
    public static final String UPNP_DISCOVERY_THING_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";
    public static final String UPNP_MANUFACTURER = "LinkPlay";
    public static final String UPNP_DEVICE_TYPE_PREFIX = "urn:schemas-upnp-org:device:";

    // Source states - must be defined before PLAYBACK_MODES map
    public static final String SOURCE_IDLE = "IDLE";
    public static final String SOURCE_AIRPLAY = "AIRPLAY";
    public static final String SOURCE_DLNA = "DLNA";
    public static final String SOURCE_NETWORK = "NETWORK";
    public static final String SOURCE_USB_DISK = "USB_DISK";
    public static final String SOURCE_HTTP_API = "HTTP_API";
    public static final String SOURCE_SPOTIFY = "SPOTIFY";
    public static final String SOURCE_LINE_IN = "LINE_IN";
    public static final String SOURCE_LINE_IN_2 = "LINE_IN_2";
    public static final String SOURCE_BLUETOOTH = "BLUETOOTH";
    public static final String SOURCE_OPTICAL = "OPTICAL";
    public static final String SOURCE_USB_DAC = "USB_DAC";
    public static final String SOURCE_MULTIROOM = "MULTIROOM";
    public static final String SOURCE_UNKNOWN = "UNKNOWN";

    // Source/Mode mappings - after source constants
    public static final Map<Integer, String> PLAYBACK_MODES = Map.ofEntries(Map.entry(0, SOURCE_IDLE), // Idling
            Map.entry(1, SOURCE_AIRPLAY), // AirPlay streaming
            Map.entry(2, SOURCE_DLNA), // DLNA streaming
            Map.entry(10, SOURCE_NETWORK), // Network content
            Map.entry(11, SOURCE_USB_DISK), // USB disk
            Map.entry(20, SOURCE_HTTP_API), // HTTP API
            Map.entry(31, SOURCE_SPOTIFY), // Spotify Connect
            Map.entry(40, SOURCE_LINE_IN), // Line-In
            Map.entry(41, SOURCE_BLUETOOTH), // Bluetooth
            Map.entry(43, SOURCE_OPTICAL), // Optical
            Map.entry(47, SOURCE_LINE_IN_2), // Line-In #2
            Map.entry(51, SOURCE_USB_DAC), // USB DAC
            Map.entry(99, SOURCE_MULTIROOM) // Multiroom Guest
    );

    // Control states
    public static final String CONTROL_PLAY = "PLAY";
    public static final String CONTROL_PAUSE = "PAUSE";
    public static final String CONTROL_STOP = "STOP";
    public static final String CONTROL_LOAD = "LOAD";

    private BindingConstants() {
        // Constants class - prevent instantiation
    }
}
