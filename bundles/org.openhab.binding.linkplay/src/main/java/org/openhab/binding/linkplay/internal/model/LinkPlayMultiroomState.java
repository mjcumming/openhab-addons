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
package org.openhab.binding.linkplay.internal.model;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Model class for multiroom status information
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayMultiroomState {

    private boolean isMaster = false;
    private boolean isSlave = false;
    private String masterIP = "";
    private String groupName = "";
    private final Map<String, SlaveDevice> slaves = new HashMap<>();

    public static class SlaveDevice {
        private final String ip;
        private final String name;
        private int volume;
        private boolean muted;

        public SlaveDevice(String ip, String name, int volume, boolean muted) {
            this.ip = ip;
            this.name = name;
            this.volume = volume;
            this.muted = muted;
        }

        public String getIp() {
            return ip;
        }

        public String getName() {
            return name;
        }

        public int getVolume() {
            return volume;
        }

        public boolean isMuted() {
            return muted;
        }

        public void setVolume(int volume) {
            this.volume = volume;
        }

        public void setMuted(boolean muted) {
            this.muted = muted;
        }
    }

    public LinkPlayMultiroomState() {
        // Initialize with defaults
        this.isMaster = false;
        this.isSlave = false;
        this.masterIP = "";
        this.slaves.clear();
        this.groupName = "";
    }

    public String getRole() {
        if (isMaster)
            return "master";
        if (isSlave)
            return "slave";
        return "standalone";
    }

    public void setMasterState() {
        this.isMaster = true;
        this.isSlave = false;
        this.masterIP = "";
    }

    public void setSlaveState(String masterIP) {
        this.isMaster = false;
        this.isSlave = true;
        this.masterIP = masterIP;
    }

    public void setStandaloneState() {
        this.isMaster = false;
        this.isSlave = false;
        this.masterIP = "";
        this.slaves.clear();
    }

    // Add slave management methods
    public void addSlave(String ip, String name, int volume, boolean muted) {
        slaves.put(ip, new SlaveDevice(ip, name, volume, muted));
    }

    public void removeSlave(String ip) {
        slaves.remove(ip);
    }

    public void clearSlaves() {
        slaves.clear();
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String name) {
        this.groupName = name;
    }

    public String getSlaveIPs() {
        return String.join(",", slaves.values().stream().map(s -> s.ip).toArray(String[]::new));
    }

    public void setSlaveIPs(String slaveIPString) {
        slaves.clear();
        if (!slaveIPString.isEmpty()) {
            for (String ip : slaveIPString.split(",")) {
                slaves.put(ip, new SlaveDevice(ip, "", 0, false));
            }
        }
    }

    public String getMasterIP() {
        return masterIP;
    }

    public void updateSlaveVolume(String ip, int volume) {
        SlaveDevice slave = slaves.get(ip);
        if (slave != null) {
            slave.setVolume(volume);
        }
    }

    public void updateSlaveMute(String ip, boolean muted) {
        SlaveDevice slave = slaves.get(ip);
        if (slave != null) {
            slave.setMuted(muted);
        }
    }

    public boolean isMaster() {
        return isMaster;
    }

    public boolean isSlave() {
        return isSlave;
    }

    public boolean isStandalone() {
        return !isMaster && !isSlave;
    }

    public boolean hasSlaves() {
        return !slaves.isEmpty();
    }

    public int getSlaveCount() {
        return slaves.size();
    }

    @Override
    public String toString() {
        return String.format("MultiroomState[role=%s, masterIP=%s, groupName=%s, slaves=%d]", getRole(), masterIP,
                groupName, slaves.size());
    }
}
