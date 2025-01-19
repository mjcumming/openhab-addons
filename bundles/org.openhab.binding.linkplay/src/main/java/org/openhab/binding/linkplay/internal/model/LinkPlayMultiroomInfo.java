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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Model class for multiroom status information
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayMultiroomInfo {

    private final String role; // "master", "slave", or "standalone"
    private final String masterIP; // If we're a slave, this might hold the master IP (if provided)
    private final List<String> slaveIPs;

    public LinkPlayMultiroomInfo(JsonObject json) {
        // 1) Read the 'group' integer. If it doesn't exist, default to 0.
        int groupVal = 0;
        if (json.has("group")) {
            try {
                groupVal = json.get("group").getAsInt();
            } catch (Exception ignored) {
                // fallback to 0
            }
        }

        // 2) The LinkPlay doc says when the device is in slave mode, we have 'master_uuid' (or 'host_uuid').
        String masterUuid = "";
        if (json.has("master_uuid")) {
            masterUuid = json.get("master_uuid").getAsString();
        } else if (json.has("host_uuid")) {
            masterUuid = json.get("host_uuid").getAsString();
        }

        // 3) Additional fields that might appear
        String foundMasterIP = json.has("master_ip") ? json.get("master_ip").getAsString() : "";

        // 4) Decide role: master, slave, or standalone
        if (groupVal > 0) {
            // device is master of groupVal slaves
            this.role = "master";
            this.masterIP = "";
        } else {
            // groupVal == 0 => either standalone or slave
            if (!masterUuid.isEmpty()) {
                // this device has a known master => it's a slave
                this.role = "slave";
                this.masterIP = foundMasterIP;
            } else {
                // truly standalone
                this.role = "standalone";
                this.masterIP = "";
            }
        }

        // 5) If role == master, parse the 'slave_list' array
        this.slaveIPs = new ArrayList<>();
        if ("master".equals(this.role)) {
            /*
             * Some firmwares do 'slave_list' or 'slaves' or store them inside 'multiroom: { ... }'.
             * The code below expects 'slave_list' as a JSON array of objects { "ip": "<IP>" }.
             */
            if (json.has("slave_list") && json.get("slave_list").isJsonArray()) {
                JsonArray slaveList = json.get("slave_list").getAsJsonArray();
                for (JsonElement elem : slaveList) {
                    if (elem.isJsonObject()) {
                        JsonObject slaveObj = elem.getAsJsonObject();
                        if (slaveObj.has("ip")) {
                            this.slaveIPs.add(slaveObj.get("ip").getAsString());
                        }
                    }
                }
            }
        }
    }

    public String getRole() {
        return role;
    }

    public String getMasterIP() {
        return masterIP;
    }

    /**
     * Returns a comma-separated string of slave IPs for logging or simple display.
     */
    public String getSlaveIPs() {
        return String.join(",", slaveIPs);
    }

    /**
     * Returns the slave IPs as a List.
     */
    public List<String> getSlaveIPList() {
        return slaveIPs;
    }
}
