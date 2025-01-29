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
package org.openhab.binding.honeywelltcc.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link HoneywellTCCBindingConstants} class defines common constants used across the binding
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class HoneywellTCCBindingConstants {

    public static final String BINDING_ID = "honeywelltcc";

    // Bridge & Thing Types
    public static final ThingTypeUID THING_TYPE_BRIDGE = new ThingTypeUID(BINDING_ID, "bridge");
    public static final ThingTypeUID THING_TYPE_THERMOSTAT = new ThingTypeUID(BINDING_ID, "thermostat");

    // Bridge Configuration Parameters
    public static final String CONFIG_USERNAME = "username";
    public static final String CONFIG_PASSWORD = "password";
    public static final String CONFIG_POLLING_INTERVAL = "pollingInterval";

    // Thing Configuration Parameters
    public static final String CONFIG_DEVICE_ID = "deviceId";
    public static final String CONFIG_LOCATION_ID = "locationId";

    // Channel IDs
    public static final String CHANNEL_TEMPERATURE = "temperature";
    public static final String CHANNEL_SETPOINT_HEAT = "setpointHeat";
    public static final String CHANNEL_SETPOINT_COOL = "setpointCool";
    public static final String CHANNEL_HUMIDITY = "humidity";
    public static final String CHANNEL_OUTDOOR_TEMPERATURE = "outdoorTemperature";
    public static final String CHANNEL_OUTDOOR_HUMIDITY = "outdoorHumidity";
    public static final String CHANNEL_FAN_MODE = "fanMode";
    public static final String CHANNEL_SYSTEM_MODE = "systemMode";
    public static final String CHANNEL_RUNNING_STATE = "runningState";
    public static final String CHANNEL_HOLD_MODE = "holdMode";

    // System Modes
    public static final String MODE_OFF = "off";
    public static final String MODE_HEAT = "heat";
    public static final String MODE_COOL = "cool";
    public static final String MODE_AUTO = "auto";
    public static final String MODE_EMERGENCY_HEAT = "emheat";

    // Fan Modes
    public static final String FAN_AUTO = "auto";
    public static final String FAN_ON = "on";
    public static final String FAN_CIRCULATE = "circulate";
    public static final String FAN_FOLLOW_SCHEDULE = "followSchedule";

    // Running States
    public static final String STATE_OFF = "off";
    public static final String STATE_HEAT = "heat";
    public static final String STATE_COOL = "cool";
    public static final String STATE_FAN = "fan";

    // Hold Modes
    public static final String HOLD_NONE = "none";
    public static final String HOLD_TEMPORARY = "temporary";
    public static final String HOLD_PERMANENT = "permanent";
} 