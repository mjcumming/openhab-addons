/**
 * Copyright (c) 2010-2025 Contributors to the openHAB project
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
package org.openhab.binding.linkplay.internal;

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link LinkPlayBindingConstants} class defines common constants used across the LinkPlay binding.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayBindingConstants {

    public static final String BINDING_ID = "linkplay";

    // Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_DEVICE = new ThingTypeUID(BINDING_ID, "device");
    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Set.of(THING_TYPE_DEVICE);

    // Thing Configuration Parameters
    public static final String PROPERTY_IP_ADDRESS = "ipAddress";
    public static final String PROPERTY_DEVICE_ID = "deviceId";
    public static final String PROPERTY_MODEL = "model";
    public static final String PROPERTY_FIRMWARE = "firmware";

    // Multiroom related properties
    public static final String PROPERTY_MULTIROOM_STATUS = "multiroomStatus";
    public static final String PROPERTY_MULTIROOM_ROLE = "multiroomRole";
    public static final String PROPERTY_MULTIROOM_MASTER = "multiroomMaster";
    public static final String PROPERTY_MULTIROOM_SLAVES = "multiroomSlaves";

    // Multiroom roles
    public static final String MULTIROOM_ROLE_MASTER = "master";
    public static final String MULTIROOM_ROLE_SLAVE = "slave";
    public static final String MULTIROOM_ROLE_NONE = "none";
}