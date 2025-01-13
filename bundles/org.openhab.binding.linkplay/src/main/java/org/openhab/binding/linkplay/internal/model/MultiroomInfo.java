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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Model class for multiroom status information
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class MultiroomInfo {
    private final String role;
    private final String masterIP;
    private final List<String> slaveIPs;

    public MultiroomInfo(JsonObject json) {
        // Extract UUID and host_uuid from status, checking both possible fields
        String uuid = json.has("uuid") ? json.get("uuid").getAsString() : "";
        String hostUuid = json.has("host_uuid") ? json.get("host_uuid").getAsString() : "";

        // Some firmware versions use master_uuid instead of host_uuid
        if (hostUuid.isEmpty() && json.has("master_uuid")) {
            hostUuid = json.get("master_uuid").getAsString();
        }

        // Determine role based on UUIDs
        if (uuid.isEmpty() || hostUuid.isEmpty()) {
            this.role = "standalone";
            this.masterIP = "";
        } else if (uuid.equals(hostUuid)) {
            this.role = "master";
            this.masterIP = "";
        } else {
            this.role = "slave";
            this.masterIP = json.has("master_ip") ? json.get("master_ip").getAsString() : "";
        }

        // Extract slave IPs for master devices
        this.slaveIPs = new ArrayList<>();
        if ("master".equals(this.role) && json.has("slave_list") && json.get("slave_list").isJsonArray()) {
            JsonArray slaveList = json.get("slave_list").getAsJsonArray();
            slaveList.forEach(element -> {
                if (element.isJsonObject() && element.getAsJsonObject().has("ip")) {
                    slaveIPs.add(element.getAsJsonObject().get("ip").getAsString());
                }
            });
        }
    }

    public String getRole() {
        return role;
    }

    public String getMasterIP() {
        return masterIP;
    }

    public String getSlaveIPs() {
        return String.join(",", slaveIPs);
    }

    public List<String> getSlaveIPList() {
        return slaveIPs;
    }
}
