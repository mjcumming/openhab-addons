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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The {@link LinkPlayMultiroomState} represents the multiroom state of a LinkPlay device
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayMultiroomState {

    private String role = "standalone";
    private String masterIP = "";
    private String slaveIPs = "";
    private String groupName = "";

    public void setStandaloneState() {
        this.role = "standalone";
        this.masterIP = "";
        this.slaveIPs = "";
        this.groupName = "";
    }

    public void setMasterState() {
        this.role = "master";
        this.masterIP = "";
    }

    public void setSlaveState(String masterIP) {
        this.role = "slave";
        this.masterIP = masterIP;
        this.slaveIPs = "";
    }

    public boolean isMaster() {
        return "master".equals(role);
    }

    public boolean isSlave() {
        return "slave".equals(role);
    }

    public boolean isStandalone() {
        return "standalone".equals(role);
    }

    public String getRole() {
        return role;
    }

    public String getMasterIP() {
        return masterIP;
    }

    public String getSlaveIPs() {
        return slaveIPs;
    }

    public void setSlaveIPs(String slaveIPs) {
        this.slaveIPs = slaveIPs;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    /**
     * Add a slave device to the multiroom group
     */
    public void addSlave(String slaveIP) {
        if (slaveIPs.isEmpty()) {
            slaveIPs = slaveIP;
        } else {
            slaveIPs += "," + slaveIP;
        }
    }

    /**
     * Clear all slaves from the multiroom group
     */
    public void clearSlaves() {
        this.slaveIPs = "";
    }

    @Override
    public String toString() {
        return String.format("MultiroomState[role=%s, masterIP=%s, groupName=%s]", role, masterIP, groupName);
    }
}
