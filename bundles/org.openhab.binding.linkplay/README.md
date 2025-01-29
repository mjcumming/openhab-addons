# LinkPlay Binding

This binding integrates audio devices built upon the **LinkPlay** platform, enabling control of multiroom audio, streaming services, and various audio inputs.

## Supported Things

- `device` - A LinkPlay-based audio device

**Examples** of compatible devices (not exhaustive):

- **Arylic**: S50 Pro+, A50, Up2Stream (Amp/Pro/mini)
- **WiiM**: Mini, Pro
- **Audio Pro**: A10, A26, A36, Addon C-series, Link 1
- **Others**: Many devices using LinkPlay technology (often identifiable by compatibility with 4STREAM/WiiM Home apps)

## Discovery

The binding supports automatic discovery via UPnP/SSDP. Discovered devices will appear in the inbox with:

- A generated Thing UID based on the device's UDN (Unique Device Name)
- The device's friendly name as the label
- The device's IP address and basic properties

**Important**: Devices must not be in a multiroom group during discovery, or only the master device will be discovered. After discovery, devices can be grouped as needed.

### Manual Configuration

```java
Thing linkplay:device:living [ ipAddress="192.168.1.100" ]
```

### Configuration Parameters

| Parameter                     | Type    | Required | Default | Description                                                    |
|------------------------------|---------|----------|---------|----------------------------------------------------------------|
| ipAddress                    | text    | yes      | -       | IP address of the LinkPlay device                              |
| deviceName                   | text    | no       | -       | Friendly name for the device                                   |
| udn                         | text    | no       | -       | UPnP Unique Device Name (auto-discovered if not specified)      |
| playerStatusPollingInterval | integer | no       | 5       | Interval (seconds) between player status updates. 0 to disable  |
| deviceStatusPollingInterval | integer | no       | 10      | Interval (seconds) between device status updates. 0 to disable  |

## Channels

### Playback Group (`playback`)

| Channel ID  | Item Type      | Description                                   |
|------------|----------------|-----------------------------------------------|
| control    | Player         | Playback control (play/pause/next/previous)   |
| title      | String         | Current track title                          |
| artist     | String         | Current track artist                         |
| album      | String         | Current track album                          |
| albumArt   | String         | URL of album artwork (via MusicBrainz/Cover Art Archive) |
| duration   | Number:Time    | Track duration in seconds                    |
| position   | Number:Time    | Current playback position in seconds         |
| volume     | Dimmer        | Volume control (0-100%)                      |
| mute       | Switch        | Mute control                                 |
| repeat     | Switch        | Enable/disable repeat mode                   |
| shuffle    | Switch        | Enable/disable shuffle mode                  |
| loopOnce   | Switch        | Enable/disable loop once mode               |
| mode       | String        | Current playback mode (read-only)            |
| input_source | String      | Input source selection (controllable)        |
| url        | String        | Play audio directly from a URL               |
| m3u        | String        | Play M3U/M3U8 playlist from URL             |
| preset     | Number        | Select and play preset (0-10)               |
| notification | String      | Play notification sound from URL             |

#### Loop Mode Combinations

The device supports various combinations of repeat, shuffle, and loop once modes. These are controlled through three independent switches that can be combined:

| Channel    | Type   | Description                                          |
|-----------|--------|------------------------------------------------------|
| repeat    | Switch | When ON, playlist will repeat continuously           |
| shuffle   | Switch | When ON, tracks will play in random order           |
| loopOnce  | Switch | When ON with repeat, playlist plays once then stops |

The binding automatically converts these switch combinations into the appropriate device loop mode:

| repeat | shuffle | loopOnce | Device Mode | Behavior                                    |
|--------|---------|----------|-------------|---------------------------------------------|
| OFF    | OFF     | OFF/ON   | 4          | Play once through playlist and stop         |
| ON     | OFF     | OFF      | 0          | Play playlist continuously in order         |
| ON     | OFF     | ON       | 1          | Play playlist once in order then stop       |
| OFF    | ON      | OFF/ON   | 3          | Play playlist once in random order          |
| ON     | ON      | OFF      | 2          | Play playlist continuously in random order  |
| ON     | ON      | ON       | 5          | Play playlist once in random order then stop|

Notes:

- The `loopOnce` switch only has effect when `repeat` is ON
- When `repeat` is OFF, the playlist will always stop after playing through once
- The shuffle setting determines if tracks play in order or randomly
- The device mode number is shown for reference but is handled internally by the binding

### System Group (`system`)

| Channel ID  | Item Type | Description                |
|------------|-----------|----------------------------|
| deviceName | String    | Device name                |
| firmware   | String    | Firmware version           |

### Network Group (`network`)

| Channel ID  | Item Type | Description                |
|------------|-----------|----------------------------|
| macAddress | String    | Device MAC address         |
| wifiSignal | Number    | WiFi signal strength (dBm) |

### Multiroom Group (`multiroom`)

The binding provides both state channels and trigger channels for multiroom control:

#### State Channels

| Channel ID   | Item Type | Description                                    |
|-------------|-----------|------------------------------------------------|
| role        | String    | Device role (master/slave/standalone)          |
| masterIP    | String    | IP of master device (if slave)                 |
| slaveIPs    | String    | List of slave IPs (if master)                 |
| groupName   | String    | Name of the multiroom group                    |
| groupVolume | Dimmer    | Group-wide volume control                      |
| groupMute   | Switch    | Group-wide mute control                        |
| join        | String    | Join a group (provide master IP)               |
| kickout     | String    | Remove a slave from group (provide slave IP)   |
| leave       | Switch    | Leave current multiroom group (if slave)       |
| ungroup     | Switch    | Disband entire group (if master)              |

### Playback Modes and Input Sources

The binding distinguishes between playback modes (read-only status) and input sources (controllable):

#### Playback Modes (`playback#mode`)

The device reports its current mode through this read-only channel:

| Mode          | Description               |
|---------------|---------------------------|
| IDLE          | No Active Source         |
| AIRPLAY       | Apple AirPlay            |
| DLNA          | DLNA Streaming           |
| NETWORK       | Network Streaming        |
| SPOTIFY       | Spotify Connect          |
| TIDAL         | Tidal Streaming          |
| LINE_IN       | Line In                  |
| LINE_IN_2     | Secondary Line In        |
| BLUETOOTH     | Bluetooth                |
| OPTICAL       | Optical Input            |
| OPTICAL_2     | Secondary Optical        |
| COAXIAL       | Coaxial Input           |
| COAXIAL_2     | Secondary Coaxial        |
| USB_DAC       | USB DAC Input            |
| UDISK         | USB Storage Device       |
| UNKNOWN       | Unknown Mode             |

#### Input Sources (`playback#input_source`)

Users can control the device's input source through this channel:

| Value         | Description                |
|--------------|----------------------------|
| WIFI         | Network (WiFi) Streaming   |
| LINE_IN      | Line In                    |
| LINE_IN_2    | Secondary Line In          |
| BLUETOOTH    | Bluetooth                  |
| OPTICAL      | Optical Input              |
| OPTICAL_2    | Secondary Optical          |
| COAXIAL      | Coaxial Input             |
| COAXIAL_2    | Secondary Coaxial          |
| USB_DAC      | USB DAC Input             |
| UDISK        | USB Storage Device         |
| UNKNOWN      | Unknown Source             |

Note: Available inputs vary by device model. The mode channel provides detailed status about the current playback source (including streaming services), while the input_source channel allows switching between physical inputs and the network streaming mode.

### URL Playback Capabilities

The binding supports various URL-based playback methods:

#### Direct URL Playback (`playback#url`)

Play audio directly from a URL. Supports common audio formats (MP3, AAC, etc.):

```java
String LivingRoom_URL "Direct URL" { channel="linkplay:device:living:playback#url" }
```

#### M3U Playlist Playback (`playback#m3u`)

Play M3U/M3U8 playlists from a URL:

```java
String LivingRoom_M3U "M3U Playlist" { channel="linkplay:device:living:playback#m3u" }
```

#### Preset Selection (`playback#preset`)

Select and play a stored preset (0-10):

```java
Number LivingRoom_Preset "Preset" { channel="linkplay:device:living:playback#preset" }
```

#### Notification Sounds (`playback#notification`)

Play notification sounds from a URL (only during NETWORK or USB playback):

```java
String LivingRoom_Notification "Notification" { channel="linkplay:device:living:playback#notification" }
```

Example rule for playing a notification:

```java
rule "Doorbell Notification"
when
    Item Doorbell changed to ON
then
    LivingRoom_Notification.sendCommand("http://example.com/doorbell.mp3")
end
```

## Full Example

### Thing Configuration

```java
Thing linkplay:device:living  "Living Room Speaker" [ ipAddress="192.168.1.100", playerStatusPollingInterval=5 ]
Thing linkplay:device:kitchen "Kitchen Speaker"     [ ipAddress="192.168.1.101", playerStatusPollingInterval=5 ]
```

### Items Configuration

```java
// Playback Status and Control
String LivingRoom_Mode    "Current Mode [%s]"     { channel="linkplay:device:living:playback#mode" }
String LivingRoom_Source  "Input Source [%s]"     { channel="linkplay:device:living:playback#input_source" }
Player LivingRoom_Control "Playback Control"      { channel="linkplay:device:living:playback#control" }

// Now Playing Information
String LivingRoom_Title   "Now Playing [%s]"     { channel="linkplay:device:living:playback#title" }
String LivingRoom_Artist  "Artist [%s]"          { channel="linkplay:device:living:playback#artist" }
String LivingRoom_Album   "Album [%s]"           { channel="linkplay:device:living:playback#album" }

// Multiroom Controls
String LivingRoom_Role    "Room Role [%s]"       { channel="linkplay:device:living:multiroom#role" }
String Kitchen_Join       "Join Group"           { channel="linkplay:device:kitchen:multiroom#join" }
Switch Kitchen_Leave      "Leave Group"          { channel="linkplay:device:kitchen:multiroom#leave" }
```

### Sitemap Configuration

```perl
sitemap linkplay label="LinkPlay Audio" {
    Frame label="Living Room" {
        Text    item=LivingRoom_Mode
        Switch  item=LivingRoom_Source mappings=[
            'WIFI'='Network',
            'LINE_IN'='Line In',
            'BLUETOOTH'='Bluetooth',
            'OPTICAL'='Optical'
        ]
        Default item=LivingRoom_Control
        Text    item=LivingRoom_Title
        Text    item=LivingRoom_Artist
        Text    item=LivingRoom_Album
    }
    
    Frame label="Multiroom" {
        Text    item=LivingRoom_Role
        Default item=Kitchen_Join
        Switch  item=Kitchen_Leave
    }
}
```

## Notes

- **Device Status**: The binding uses HTTP polling to track player and multiroom states, as UPnP events are not consistently implemented across LinkPlay devices.
- **Extended Control**: The UPnP Control binding can be used alongside this binding for additional playlist management features.
- **Volume Control**: Individual slave volumes can be set, but group volume changes from the master will override them.
- **Album Artwork**: Album art URLs are retrieved using the MusicBrainz/Cover Art Archive service based on track metadata.
- **Firmware Variations**: Some LinkPlay devices may have older firmware that doesn't support all features.
- **Network Stability**: A stable network connection is important for reliable multiroom synchronization.

## Troubleshooting

1. **Device Not Discovered**
   - Ensure the device is on the same network
   - Try manual configuration with the IP address
   - Check if UPnP/SSDP is enabled on your network

2. **Multiroom Issues**
   - Verify all devices have stable network connections
   - Check that devices have compatible firmware versions
   - Try ungrouping and regrouping devices

3. **Missing Metadata**
   - Album art and extended metadata require internet connectivity
   - Not all sources provide complete metadata

## Developer Notes

The binding primarily uses:

- HTTP API for device control, status monitoring, and multiroom management
- UPnP/SSDP for initial device discovery only
- Periodic HTTP polling for reliable state tracking
- MusicBrainz/Cover Art Archive API for enhanced metadata

While some LinkPlay devices offer UART interfaces, this is not consistently available across the device ecosystem and is not utilized by this binding.

For detailed API documentation, see:

- [LinkPlay HTTP API](https://developer.arylic.com/httpapi/#http-api)
