# LinkPlay Binding

This binding integrates LinkPlay-based audio devices with group (multiroom) capabilities. It provides control of audio playback, volume, source selection, and group functionality.

## Features

- Basic playback control (play/pause/next/previous)
- Volume control (including mute)
- Group functionality (master/slave configuration)
- Source selection (WiFi/Bluetooth/Line-in)
- UPnP device discovery
- Event-based status updates
- Extensive device status information

## Supported Things

- `device` - A LinkPlay-based audio device

## Discovery

The binding supports automatic discovery of LinkPlay devices through:

- **UPnP (DLNA) discovery**: Automatically detects devices on your network that support UPnP.

Discovered devices will appear in the inbox with:

- Thing ID: Generated from the device's unique identifier
- Label: Device name from the network
- Properties: IP address, device ID, and firmware version

## Thing Configuration

### Manual Configuration

| Parameter       | Description                                      | Required | Default |
|------------------|--------------------------------------------------|----------|---------|
| `ipAddress`      | IP address of the device                        | Yes      |         |
| `deviceName`     | Friendly name for the LinkPlay device           | No       |         |
| `pollingInterval`| Interval in seconds between device status updates (Advanced) | No | 10      |

### Configuration Notes

- Polling interval is used to refresh device status at regular intervals.
- Configurations can be updated in the `thing` file or via the UI.

## Channels

### Media Control Channels

| Channel Type ID | Item Type | Description                    | Read/Write |
|-----------------|-----------|--------------------------------|------------|
| control         | Player    | Media control                 | R/W        |
| title           | String    | Title of the current track    | R          |
| artist          | String    | Artist of the current track   | R          |
| album           | String    | Album of the current track    | R          |
| volume          | Dimmer    | Volume control (0-100%)       | R/W        |
| mute            | Switch    | Mute/unmute the audio         | R/W        |

#### Control Commands

The `control` channel accepts standard `Player` item commands:

- PLAY: Start playback
- PAUSE: Pause playback
- NEXT: Skip to the next track
- PREVIOUS: Skip to the previous track
- STOP: Stop playback

### Playback Control Channels

| Channel Type ID | Item Type | Description                    | Read/Write |
|-----------------|-----------|--------------------------------|------------|
| repeat          | String    | Repeat mode (off/all/single)  | R/W        |
| shuffle         | Switch    | Shuffle mode                  | R/W        |
| source          | String    | Input source selection        | R/W        |

#### Repeat Mode Options

- `off`: No repeat
- `all`: Repeat all tracks
- `single`: Repeat the current track

#### Source Options

- `wifi`: Network streaming
- `bluetooth`: Bluetooth input
- `line-in`: Analog input
- `optical`: Digital optical input

### Device Metadata Channels

| Channel Type ID | Item Type | Description                    | Read/Write |
|-----------------|-----------|--------------------------------|------------|
| deviceName      | String    | Friendly name of the device    | R          |
| firmware        | String    | Firmware version of the device| R          |
| macAddress      | String    | MAC address of the device      | R          |
| ipAddress       | String    | IP address of the device       | R          |
| wifiSignal      | Percent   | WiFi signal strength           | R          |

### Group Control Channels

| Channel Type ID    | Item Type | Description                        | Read/Write |
|--------------------|-----------|------------------------------------|------------|
| groupRole          | String    | Device role (standalone/master/slave) | R      |
| groupMasterIP      | String    | IP address of the master device    | R          |
| groupSlaveIPs      | String    | List of slave device IPs           | R          |
| groupJoin          | String    | Join a group (specify master IP)   | W          |
| groupLeave         | String    | Leave the current group            | W          |
| groupUngroup       | String    | Ungroup all slaves (master only)   | W          |
| groupSlaveKickout  | String    | Remove a specific slave from group | W          |
| groupVolume        | Dimmer    | Group-wide volume control          | R/W        |
| groupMute          | Switch    | Group-wide mute control            | R/W        |

## Example Configuration

### Example Thing Configuration

```plaintext
Thing linkplay:device:living [ ipAddress="192.168.1.100", pollingInterval=15 ]
