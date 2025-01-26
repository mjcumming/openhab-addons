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

    // Thing Type UIDs
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
    public static final String CHANNEL_CONTROL = "control";
    public static final String CHANNEL_TITLE = "title";
    public static final String CHANNEL_ARTIST = "artist";
    public static final String CHANNEL_ALBUM = "album";
    public static final String CHANNEL_DURATION = "duration";
    public static final String CHANNEL_POSITION = "position";
    public static final String CHANNEL_VOLUME = "volume";
    public static final String CHANNEL_MUTE = "mute";
    public static final String CHANNEL_REPEAT = "repeat";
    public static final String CHANNEL_SHUFFLE = "shuffle";
    public static final String CHANNEL_INPUT_SOURCE = "input_source";
    public static final String CHANNEL_MODE = "mode";
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

    // Mode constants (read-only status)
    public static final String MODE_UNKNOWN = "UNKNOWN";
    public static final String MODE_IDLE = "IDLE";
    public static final String MODE_AIRPLAY = "AIRPLAY";
    public static final String MODE_DLNA = "DLNA";
    public static final String MODE_SPOTIFY = "SPOTIFY";
    public static final String MODE_TIDAL = "TIDAL";
    public static final String MODE_NETWORK = "NETWORK";
    public static final String MODE_LINE_IN = "LINE_IN";
    public static final String MODE_LINE_IN_2 = "LINE_IN_2";
    public static final String MODE_BLUETOOTH = "BLUETOOTH";
    public static final String MODE_OPTICAL = "OPTICAL";
    public static final String MODE_OPTICAL_2 = "OPTICAL_2";
    public static final String MODE_COAXIAL = "COAXIAL";
    public static final String MODE_COAXIAL_2 = "COAXIAL_2";
    public static final String MODE_USB_DAC = "USB_DAC";
    public static final String MODE_UDISK = "UDISK";

    // Input source constants (controllable)
    public static final String INPUT_UNKNOWN = "UNKNOWN";
    public static final String INPUT_WIFI = "WIFI";
    public static final String INPUT_LINE_IN = "LINE_IN";
    public static final String INPUT_LINE_IN_2 = "LINE_IN_2";
    public static final String INPUT_BLUETOOTH = "BLUETOOTH";
    public static final String INPUT_OPTICAL = "OPTICAL";
    public static final String INPUT_OPTICAL_2 = "OPTICAL_2";
    public static final String INPUT_COAXIAL = "COAXIAL";
    public static final String INPUT_COAXIAL_2 = "COAXIAL_2";
    public static final String INPUT_USB_DAC = "USB_DAC";
    public static final String INPUT_UDISK = "UDISK";

    // Map of input sources to API commands
    public static final Map<String, String> INPUT_SOURCE_COMMANDS = Map.ofEntries(Map.entry(INPUT_WIFI, "wifi"),
            Map.entry(INPUT_LINE_IN, "line-in"), Map.entry(INPUT_BLUETOOTH, "bluetooth"),
            Map.entry(INPUT_OPTICAL, "optical"), Map.entry(INPUT_COAXIAL, "co-axial"),
            Map.entry(INPUT_LINE_IN_2, "line-in2"), Map.entry(INPUT_USB_DAC, "PCUSB"), Map.entry(INPUT_UDISK, "udisk"));

    // Playback control constants
    public static final String CONTROL_PLAY = "play";
    public static final String CONTROL_PAUSE = "pause";
    public static final String CONTROL_STOP = "stop";
    public static final String CONTROL_LOAD = "load";

    // Source constants (legacy - for backward compatibility)
    public static final String SOURCE_UNKNOWN = "UNKNOWN";

    private BindingConstants() {
        // Constants class - prevent instantiation
    }
}
