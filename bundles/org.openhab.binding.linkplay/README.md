## LinkPlay Binding

This binding integrates audio devices built upon the **LinkPlay** platform. Many brands embed LinkPlay modules in their products (Arylic, WiiM, Audio Pro, and more), enabling multiroom audio, streaming services, AirPlay, and DLNA/UPnP capabilities.

### **Supported Devices**

> **Examples** (not exhaustive):
>
> - **Arylic**: S50 Pro+, A50, Up2Stream (Amp/Pro/mini) boards  
> - **WiiM**: WiiM Mini, WiiM Pro  
> - **Audio Pro**: A10, A26, A36, Addon C-series, Link 1, etc.  
> - **Others**: August WS300, Auna, Bauhn, Bem, Cowin, Dayton Audio, DOSS, Edifier, Energy Sistem, FABRIQ, GGMM…  
>
> Since LinkPlay is an “under the hood” technology, many manufacturers do not explicitly mention it. If your device supports multiroom streaming and LinkPlay apps (4STREAM, WiiM Home, etc.), it likely works with this binding.

---

## Features

1. **Playback Control**  
   \- **Play, Pause, Stop, Next/Previous** track.  
2. **Volume & Mute**  
   \- Per-device volume (0–100%) and mute toggles.  
3. **Multiroom Management**  
   \- Master/slave group configuration.  
   \- The master device sees and controls volume/mute for each slave.  
   \- **Note:** Once a device is in slave mode, it no longer broadcasts its own UPnP events—control is via the master.  
4. **Source Selection**  
   \- Wi-Fi (DLNA/AirPlay/Spotify), Bluetooth, Line-In, Optical, etc.  
5. **Metadata & Device Info**  
   \- Title, Artist, Album, Firmware version, IP address, Wi-Fi signal, etc.  
6. **UPnP Device Discovery**  
   \- Automatic detection of LinkPlay-based media renderers on your network.

---

## Discovery

By default, the binding listens for **UPnP/SSDP** announcements to discover LinkPlay devices.  
- They appear in the **Inbox** with a generated UID based on the device UDN.  
- The label is derived from the device’s friendly name.  
- If you have multiple LinkPlay devices, each will appear with its IP address and basic properties.

---

## Thing Configuration

You can add devices manually (e.g. in a `*.things` file or via the UI) if discovery is unsuccessful or you prefer static IP addresses.

| Parameter          | Description                                          | Required | Default |
|--------------------|------------------------------------------------------|----------|---------|
| `ipAddress`        | IP address of the device                             | Yes      | —       |
| `deviceName`       | Friendly name for the LinkPlay device               | No       | —       |
| `udn`              | Unique Device Name (UPnP) — if known                 | No       | —       |
| `pollingInterval`  | Interval (seconds) for periodic status polling       | No       | 10      |

**Notes**:  
- The binding uses `ipAddress` plus periodic polling to stay updated on device status (volume, track, etc.).  
- The `udn` can be discovered automatically from the HTTP status in many cases, but if you already know it, you can supply it.

---

## Channels

The binding provides channels for both local device playback control and multiroom grouping. Below is an outline of major channels:

### Playback-Related

| Channel ID     | Item Type  | Description                                   |
|----------------|------------|-----------------------------------------------|
| **control**    | Player     | Basic playback commands (play/pause/etc.)     |
| **title**      | String     | Current track title (if available)           |
| **artist**     | String     | Current track artist (if available)          |
| **album**      | String     | Current track album (if available)           |
| **albumArt**   | String     | (URL) Album art from UPnP events             |
| **volume**     | Dimmer     | Volume (0–100)                                |
| **mute**       | Switch     | Mute/unmute                                   |
| **repeat**     | Switch     | Repeat on/off (simple mode)                  |
| **shuffle**    | Switch     | Shuffle on/off                                |
| **source**     | String     | Source selection (wifi, bluetooth, line-in)   |

### Device/System Info

| Channel ID       | Item Type | Description                              |
|------------------|----------|------------------------------------------|
| **deviceName**   | String   | Friendly name from device config         |
| **firmware**     | String   | Firmware version                         |
| **ipAddress**    | String   | Current IP address of the device         |
| **macAddress**   | String   | MAC address (if available)              |
| **wifiSignal**   | Number   | Wi-Fi signal strength (dBm or 0–100)     |

### Multiroom / Grouping

| Channel ID         | Item Type | Description                                                 |
|--------------------|----------|-------------------------------------------------------------|
| **role**           | String   | `standalone`, `master`, or `slave`                          |
| **masterIP**       | String   | IP of master (shown on a slave device)                      |
| **slaveIPs**       | String   | IP addresses of any slaves (only meaningful on the master)  |
| **join**           | String   | Provide a master IP to join that group                      |
| **leave**          | Switch   | Leave the current group (if slave)                          |
| **ungroup**        | Switch   | Disband entire group (if master)                            |
| **kickout**        | String   | Remove a specified slave IP from the group (master only)    |
| **groupVolume**    | Dimmer   | Group-wide volume (master device controlling slaves)        |
| **groupMute**      | Switch   | Group-wide mute (master device controlling slaves)          |

---

## Multiroom Behavior

- **Master**: The device hosting the audio stream. All slaves sync to this.  
- **Slave**: A device that has joined the master’s group. **It no longer sends its own UPnP events**; the master is responsible for controlling it.  
- **Actions**:  
  - **Join**: Provide the master IP. The device becomes a slave.  
  - **Leave**: If a device is currently a slave, it can leave the group.  
  - **Ungroup**: Break up the entire group from the master side.  
  - **Kickout**: The master can forcibly remove a particular slave from the group.  

Within openHAB, you might link these group channels to rules or UI elements to let you dynamically form or dissolve multiroom groups.

---

## Example Setup

### Things File

```java
Thing linkplay:device:bedroom  [ ipAddress="192.168.1.101", deviceName="Bedroom Speaker", pollingInterval=15 ]
Thing linkplay:device:kitchen  [ ipAddress="192.168.1.102", deviceName="Kitchen Speaker" ]
```

### Items File

```java
// Basic playback
Player  Bedroom_Control  "Bedroom Control"  { channel="linkplay:device:bedroom:control" }
Dimmer  Bedroom_Volume  "Bedroom Volume"   { channel="linkplay:device:bedroom:volume" }
Switch  Bedroom_Mute    "Bedroom Mute"     { channel="linkplay:device:bedroom:mute" }

// Multiroom
Switch  Bedroom_Leave   "Leave Group"      { channel="linkplay:device:bedroom:leave" }
String  Bedroom_Join    "Join Group"       { channel="linkplay:device:bedroom:join" }
...
```

### Usage Notes

- If you **join** the kitchen speaker to the bedroom speaker (master), the kitchen device becomes a slave.  
- The bedroom device’s “slaveIPs” channel might then reflect the kitchen speaker’s IP.  
- The kitchen device’s “role” channel will show “slave,” and it might not broadcast its own playback data.

---

## Limitations & Notes

- **UPnP Subscription**: In a multiroom group, only the master device’s events are relevant. Slaves no longer publish separate UPnP events.  
- **Volume Differences**: You can still set an individual slave’s volume, but group volume typically overrides it if changed from the master.  
- **Inconsistent Firmware**: Some LinkPlay-based products have older firmware that may not support all commands.  
- **Partial API Coverage**: The LinkPlay HTTP API is extensive. This binding implements the core features (media control, multiroom, volume, etc.). Additional endpoints (e.g., advanced Wi-Fi config) might not be exposed directly.

---

## Further Reading

- [Official openHAB Binding Docs](https://www.openhab.org/docs/developer/bindings/)  
- [LinkPlay Developer Docs](https://developer.arylic.com/)  
- Community discussion in [Arylic / LinkPlay forums and user groups]

---

## Conclusion

This binding supports a **wide range** of LinkPlay-based audio devices. It provides multiroom grouping, basic playback, volume/mute, and source control. If your device is not discovered automatically, you can add it manually via `ipAddress`. For multiroom grouping, you can use the group channels to **join** or **ungroup** devices at runtime.

Please **report issues or suggestions** in the openHAB community forums or GitHub repository to help refine LinkPlay support further.
