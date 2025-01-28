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
 * The {@link MultiroomState} represents the multiroom state of a LinkPlay device.
 * This class manages the device's role in a multiroom setup, which can be:
 * - standalone: Device operates independently
 * - master: Device controls one or more slave devices
 * - slave: Device is controlled by a master device
 *
 * The state includes information about:
 * - The device's current role (standalone/master/slave)
 * - Master device's IP address (for slave devices)
 * - List of slave device IPs (for master devices)
 * - Group name for multiroom identification
 *
 * State transitions are managed through dedicated methods:
 * - setStandaloneState(): Clears all group associations
 * - setMasterState(): Transitions to master role
 * - setSlaveState(ip): Transitions to slave role with master IP
 *
 * Note: During group formation/breakup, devices may temporarily have inconsistent state
 * as changes propagate through the network. The GroupManager handles these transitions
 * and ensures eventual consistency.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class MultiroomState {

    /**
     * Internal enum to represent device roles.
     * String values match the API expectations.
     */
    private enum Role {
        STANDALONE("standalone"),
        MASTER("master"),
        SLAVE("slave");

        private final String value;

        Role(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /** Current role of the device: "standalone", "master", or "slave" */
    private Role role = Role.STANDALONE;

    /** IP address of the master device when in slave mode */
    private String masterIP = "";

    /** Comma-separated list of slave device IPs when in master mode */
    private String slaveIPs = "";

    /** Name of the multiroom group this device belongs to */
    private String groupName = "";

    /**
     * Sets the device to standalone mode, clearing all multiroom associations.
     * Used when device leaves a multiroom group.
     */
    public void setStandaloneState() {
        this.role = Role.STANDALONE;
        this.masterIP = "";
        this.slaveIPs = "";
        this.groupName = "";
    }

    /**
     * Sets the device to master mode.
     * A master device can control one or more slave devices in a multiroom setup.
     */
    public void setMasterState() {
        this.role = Role.MASTER;
        this.masterIP = "";
    }

    /**
     * Sets the device to slave mode with specified master device.
     * 
     * @param masterIP IP address of the master device this slave will follow
     */
    public void setSlaveState(String masterIP) {
        this.role = Role.SLAVE;
        this.masterIP = masterIP;
        this.slaveIPs = "";
    }

    /**
     * Checks if device is in master mode.
     * 
     * @return true if device is a master in a multiroom setup
     */
    public boolean isMaster() {
        return role == Role.MASTER;
    }

    /**
     * Checks if device is in slave mode.
     * 
     * @return true if device is a slave in a multiroom setup
     */
    public boolean isSlave() {
        return role == Role.SLAVE;
    }

    /**
     * Checks if device is in standalone mode.
     * 
     * @return true if device is not part of a multiroom setup
     */
    public boolean isStandalone() {
        return role == Role.STANDALONE;
    }

    /**
     * Gets the string representation of the current role.
     * WARNING: This method should ONLY be used for channel state updates.
     * For role checking, use isStandalone(), isMaster(), or isSlave() instead.
     * 
     * @return Current role as string: "standalone", "master", or "slave"
     */
    public String getRole() {
        return role.toString();
    }

    /**
     * Gets the IP address of the master device.
     * Only relevant when device is in slave mode.
     * 
     * @return IP address of master device or empty string if not in slave mode
     */
    public String getMasterIP() {
        return masterIP;
    }

    /**
     * Gets the comma-separated list of slave device IPs.
     * Only relevant when device is in master mode.
     * 
     * @return Comma-separated list of slave IPs or empty string if no slaves
     */
    public String getSlaveIPs() {
        return slaveIPs;
    }

    /**
     * Sets the list of slave device IPs.
     * Only relevant when device is in master mode.
     * 
     * @param slaveIPs Comma-separated list of slave device IP addresses
     */
    public void setSlaveIPs(String slaveIPs) {
        this.slaveIPs = slaveIPs;
    }

    /**
     * Gets the name of the multiroom group.
     * 
     * @return Name of the multiroom group or empty string if not in a group
     */
    public String getGroupName() {
        return groupName;
    }

    /**
     * Sets the name of the multiroom group.
     * 
     * @param groupName Name to identify the multiroom group
     */
    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    /**
     * Adds a slave device to the multiroom group.
     * Only relevant when device is in master mode.
     * 
     * @param slaveIP IP address of the slave device to add
     */
    public void addSlave(String slaveIP) {
        if (slaveIPs.isEmpty()) {
            slaveIPs = slaveIP;
        } else {
            slaveIPs += "," + slaveIP;
        }
    }

    /**
     * Removes all slave devices from the multiroom group.
     * Only relevant when device is in master mode.
     */
    public void clearSlaves() {
        this.slaveIPs = "";
    }

    /**
     * Returns a string representation of the multiroom state.
     * 
     * @return String containing role, master IP, and group name
     */
    @Override
    public String toString() {
        return String.format("MultiroomState[role=%s, masterIP=%s, groupName=%s]", role, masterIP, groupName);
    }
}
