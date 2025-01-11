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
import org.eclipse.jdt.annotation.Nullable;

/**
 * The {@link MultiroomInfo} represents the multiroom status of a LinkPlay device
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class MultiroomInfo {
    private String role = "standalone"; // Can be "standalone", "master", or "slave"
    private @Nullable String masterIP;
    private final List<String> slaveIPs = new ArrayList<>();

    public String getRole() {
        return role;
    }

    public String getMasterIP() {
        String ip = masterIP;
        return ip != null ? ip : "";
    }

    public List<String> getSlaveIPs() {
        return new ArrayList<>(slaveIPs);
    }

    public static class Builder {
        private final MultiroomInfo info;

        public Builder() {
            info = new MultiroomInfo();
        }

        public Builder withRole(String role) {
            info.role = role;
            return this;
        }

        public Builder withMasterIP(@Nullable String masterIP) {
            info.masterIP = masterIP;
            return this;
        }

        public Builder withSlaveIPs(List<String> slaveIPs) {
            info.slaveIPs.clear();
            info.slaveIPs.addAll(slaveIPs);
            return this;
        }

        public MultiroomInfo build() {
            return info;
        }
    }
}
