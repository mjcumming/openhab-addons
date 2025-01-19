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
| albumArt   | String         | URL of album artwork                         |
| duration   | Number:Time    | Track duration in seconds                    |
| position   | Number:Time    | Current playback position in seconds         |
| volume     | Dimmer        | Volume control (0-100%)                      |
| mute       | Switch        | Mute control                                 |
| repeat     | Switch        | Repeat mode                                  |
| shuffle    | Switch        | Shuffle mode                                 |
| source     | String        | Audio source selection                       |

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

| Channel ID   | Item Type | Description                                    |
|-------------|-----------|------------------------------------------------|
| role        | String    | Device role (master/slave/standalone)          |
| masterIP    | String    | IP of master device (if slave)                 |
| slaveIPs    | String    | List of slave IPs (if master)                 |
| groupName   | String    | Name of the multiroom group                    |
| join        | String    | Join a group (provide master IP)               |
| leave       | Switch    | Leave current group                            |
| ungroup     | Switch    | Disband entire group (if master)              |
| kickout     | String    | Remove a slave from group (provide slave IP)   |
| groupVolume | Dimmer    | Group-wide volume control                      |
| groupMute   | Switch    | Group-wide mute control                        |

## Full Example

### Thing Configuration

```java
Thing linkplay:device:living  "Living Room Speaker" [ ipAddress="192.168.1.100", playerStatusPollingInterval=5 ]
Thing linkplay:device:kitchen "Kitchen Speaker"     [ ipAddress="192.168.1.101", playerStatusPollingInterval=5 ]
```

### Items Configuration

```java
// Playback Controls
Player LivingRoom_Control  "Living Room Control"  { channel="linkplay:device:living:playback#control" }
Dimmer LivingRoom_Volume  "Living Room Volume"   { channel="linkplay:device:living:playback#volume" }
Switch LivingRoom_Mute    "Living Room Mute"     { channel="linkplay:device:living:playback#mute" }

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
        Default item=LivingRoom_Control
        Slider  item=LivingRoom_Volume
        Switch  item=LivingRoom_Mute
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

- **UPnP Events**: In a multiroom group, only the master device broadcasts UPnP events. Slave devices are controlled through the master.
- **Volume Control**: Individual slave volumes can be set, but group volume changes from the master will override them.
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

The binding uses multiple communication methods:

- HTTP API for primary control and status
- UPnP for device discovery and real-time events
- Periodic polling as a fallback mechanism

For detailed API documentation, see:

- [LinkPlay HTTP API](https://developer.arylic.com/httpapi/#http-api)
- [OpenHAB Binding Development](https://www.openhab.org/docs/developer/bindings/)
