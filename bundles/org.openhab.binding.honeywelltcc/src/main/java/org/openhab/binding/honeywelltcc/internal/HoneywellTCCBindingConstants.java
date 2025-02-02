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

import javax.measure.Unit;
import javax.measure.quantity.Dimensionless;
import javax.measure.quantity.Temperature;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ThingTypeUID;

/**
 * Constants for the Honeywell TCC binding.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class HoneywellTCCBindingConstants {

    // Logging
    public static final String LOG_CATEGORY = "org.openhab.binding.honeywelltcc";
    public static final String LOG_SUBCATEGORY_CLIENT = "client";
    public static final String LOG_SUBCATEGORY_HANDLER = "handler";

    // Binding ID
    public static final String BINDING_ID = "honeywelltcc";

    // Thing Types
    public static final ThingTypeUID THING_TYPE_ACCOUNT = new ThingTypeUID(BINDING_ID, "account");
    public static final ThingTypeUID THING_TYPE_THERMOSTAT = new ThingTypeUID(BINDING_ID, "thermostat");

    // Configuration Properties
    public static final String CONFIG_USERNAME = "username";
    public static final String CONFIG_PASSWORD = "password";
    public static final String CONFIG_REFRESH = "refresh";
    public static final String CONFIG_TIMEOUT = "timeout";
    public static final String CONFIG_DEVICE_ID = "deviceId";
    public static final String CONFIG_LOCATION_ID = "locationId";

    // Default values
    public static final int DEFAULT_REFRESH_MINUTES = 2;
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final int MIN_REFRESH_MINUTES = 1;
    public static final int MAX_REFRESH_MINUTES = 60;
    public static final int MIN_TIMEOUT_SECONDS = 5;
    public static final int MAX_TIMEOUT_SECONDS = 120;
    public static final int MIN_SETPOINT = 40; // Fahrenheit
    public static final int MAX_SETPOINT = 90; // Fahrenheit
    public static final int MIN_HOLD_TIME = 15; // minutes
    public static final int MAX_HOLD_TIME = 1440; // minutes (24 hours)

    // Base URLs
    public static final String BASE_URL = "https://www.mytotalconnectcomfort.com/portal";

    // API Endpoints
    public static final String ENDPOINT_LOGIN = "/Account/LogOn";
    public static final String ENDPOINT_LOCATIONS = "/Location/GetLocationListData";
    public static final String ENDPOINT_DEVICE_DATA = "/Device/CheckDataSession/%s";
    public static final String ENDPOINT_DEVICE_SETTINGS = "/Device/SubmitControlScreenChanges";

    // HTTP Client Constants
    public static final int HTTP_STATUS_OK = 200;
    public static final int HTTP_STATUS_REDIRECT = 302;
    public static final int HTTP_STATUS_UNAUTHORIZED = 401;
    public static final int HTTP_STATUS_TOO_MANY_REQUESTS = 429;
    public static final int HTTP_REQUEST_TIMEOUT_SEC = 30;
    public static final int HTTP_MAX_RETRIES = 3;
    public static final long HTTP_RETRY_DELAY_MS = 1000;
    public static final long HTTP_SESSION_TIMEOUT_MS = 3600000; // 1 hour session timeout
    public static final int HTTP_RATE_LIMIT_BACKOFF_SEC = 300; // 5 minutes
    public static final int HTTP_MAX_CONSECUTIVE_FAILURES = 3;
    public static final int KEEPALIVE_INTERVAL_SEC = 30; // 30 seconds keepalive interval

    // Content Types
    public static final String CONTENT_TYPE_JSON = "application/json";
    public static final String CONTENT_TYPE_HTML = "text/html";
    public static final String CONTENT_TYPE_FORM = "application/x-www-form-urlencoded";

    // HTTP Headers
    public static final String HEADER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";
    public static final String HEADER_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8";
    public static final String HEADER_ACCEPT_LANGUAGE = "en-US,en;q=0.9";
    public static final String HEADER_CONNECTION = "keep-alive";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_REFERER = "Referer";
    public static final String HEADER_COOKIE = "Cookie";

    // Form Fields
    public static final String FORM_USERNAME = "UserName";
    public static final String FORM_PASSWORD = "Password";
    public static final String FORM_REMEMBER_ME = "RememberMe";
    public static final String FORM_TIME_OFFSET = "timeOffset";
    public static final String FORM_TIME_OFFSET_VALUE = "480";

    // API Cookies
    public static final String COOKIE_AUTH = ".ASPXAUTH";
    public static final String COOKIE_SESSION = "sessionId";

    // Channel Groups
    public static final String GROUP_STATUS = "status";
    public static final String GROUP_SETTINGS = "settings";
    public static final String GROUP_SCHEDULE = "schedule";
    public static final String GROUP_DEVICE = "device";

    // Status Channels
    public static final String CHANNEL_INDOOR_TEMPERATURE = "indoorTemperature";
    public static final String CHANNEL_INDOOR_HUMIDITY = "indoorHumidity";
    public static final String CHANNEL_OUTDOOR_TEMPERATURE = "outdoorTemperature";
    public static final String CHANNEL_OUTDOOR_HUMIDITY = "outdoorHumidity";
    public static final String CHANNEL_OPERATING_STATE = "operatingState";
    public static final String CHANNEL_FOLLOWING_SCHEDULE = "followingSchedule";
    public static final String CHANNEL_EQUIPMENT_STATUS = "equipmentStatus";
    public static final String CHANNEL_FAN_CLEAN_TIME = "fanCleanTime";
    public static final String CHANNEL_FILTER_STATUS = "filterStatus";
    public static final String CHANNEL_UV_LAMP_STATUS = "uvLampStatus";
    public static final String CHANNEL_ALERTS = "alerts";
    public static final String CHANNEL_LAST_UPDATE = "lastUpdate";

    // Settings Channels
    public static final String CHANNEL_MODE = "mode";
    public static final String CHANNEL_FAN_MODE = "fanMode";
    public static final String CHANNEL_HEAT_SETPOINT = "heatSetpoint";
    public static final String CHANNEL_COOL_SETPOINT = "coolSetpoint";
    public static final String CHANNEL_DUAL_SETPOINT = "dualSetpoint";
    public static final String CHANNEL_HOLD_MODE = "holdMode";
    public static final String CHANNEL_TEMPORARY_HOLD = "temporaryHold";
    public static final String CHANNEL_VACATION_HOLD = "vacationHold";
    public static final String CHANNEL_DEADBAND = "deadband";
    public static final String CHANNEL_TEMPERATURE_UNIT = "temperatureUnit";
    public static final String CHANNEL_SETPOINT_MODE = "setpointMode";
    public static final String CHANNEL_SETPOINT_COOL = "setpointCool";
    public static final String CHANNEL_SETPOINT_HEAT = "setpointHeat";

    // Schedule Channels
    public static final String CHANNEL_SCHEDULE_PERIOD = "schedulePeriod";
    public static final String CHANNEL_NEXT_PERIOD_TIME = "nextPeriodTime";
    public static final String CHANNEL_SCHEDULE_ENABLED = "scheduleEnabled";
    public static final String CHANNEL_WAKE_TIME = "wakeTime";
    public static final String CHANNEL_WAKE_HEAT_SETPOINT = "wakeHeatSetpoint";
    public static final String CHANNEL_WAKE_COOL_SETPOINT = "wakeCoolSetpoint";
    public static final String CHANNEL_LEAVE_TIME = "leaveTime";
    public static final String CHANNEL_LEAVE_HEAT_SETPOINT = "leaveHeatSetpoint";
    public static final String CHANNEL_LEAVE_COOL_SETPOINT = "leaveCoolSetpoint";
    public static final String CHANNEL_RETURN_TIME = "returnTime";
    public static final String CHANNEL_RETURN_HEAT_SETPOINT = "returnHeatSetpoint";
    public static final String CHANNEL_RETURN_COOL_SETPOINT = "returnCoolSetpoint";
    public static final String CHANNEL_SLEEP_TIME = "sleepTime";
    public static final String CHANNEL_SLEEP_HEAT_SETPOINT = "sleepHeatSetpoint";
    public static final String CHANNEL_SLEEP_COOL_SETPOINT = "sleepCoolSetpoint";

    // Device Channels
    public static final String CHANNEL_RAW_DATA = "rawData";

    // API Response Keys
    public static final String KEY_SUCCESS = "success";
    public static final String KEY_DEVICE_ID = "DeviceID";
    public static final String KEY_LOCATION_ID = "LocationID";
    public static final String KEY_GATEWAY_ID = "GatewayInstanceID";
    public static final String KEY_TEMPERATURE = "DispTemperature";
    public static final String KEY_HUMIDITY = "IndoorHumidity";
    public static final String KEY_OUTDOOR_TEMPERATURE = "OutdoorTemperature";
    public static final String KEY_OUTDOOR_HUMIDITY = "OutdoorHumidity";
    public static final String KEY_SYSTEM_STATUS = "SystemSwitchPosition";
    public static final String KEY_FAN_STATUS = "FanPosition";
    public static final String KEY_HEAT_SETPOINT = "HeatSetpoint";
    public static final String KEY_COOL_SETPOINT = "CoolSetpoint";
    public static final String KEY_HOLD_STATUS = "StatusHeat";
    public static final String KEY_SCHEDULE_STATUS = "ScheduleCapable";
    public static final String KEY_EQUIPMENT_STATUS = "EquipmentOutputStatus";
    public static final String KEY_NEXT_PERIOD = "NextPeriod";
    public static final String KEY_NEXT_TIME = "NextTime";
    public static final String KEY_CURRENT_PERIOD = "CurrentPeriod";
    public static final String KEY_VACATION_HOLD = "VacationHold";
    public static final String KEY_DUAL_SETPOINT = "DualSetpointStatus";
    public static final String KEY_DEADBAND = "Deadband";
    public static final String KEY_IS_ALIVE = "IsAlive";
    public static final String KEY_DISPLAY_UNITS = "DisplayUnits";
    public static final String KEY_FILTER_DAYS = "FilterRemainingDays";
    public static final String KEY_UV_LAMP_STATUS = "UVLampStatus";
    public static final String KEY_ALERTS = "AlertsActive";

    // System Mode Values
    public static final int MODE_OFF = 0;
    public static final int MODE_HEAT = 1;
    public static final int MODE_COOL = 2;
    public static final int MODE_AUTO = 3;
    public static final int MODE_EMERGENCY_HEAT = 4;

    // Fan Mode Values
    public static final int FAN_AUTO = 0;
    public static final int FAN_ON = 1;
    public static final int FAN_CIRCULATE = 2;

    // Hold Mode Values
    public static final int HOLD_NONE = 0;
    public static final int HOLD_TEMPORARY = 1;
    public static final int HOLD_PERMANENT = 2;
    public static final int HOLD_VACATION = 3;

    // Schedule Period Values
    public static final int PERIOD_WAKE = 0;
    public static final int PERIOD_LEAVE = 1;
    public static final int PERIOD_RETURN = 2;
    public static final int PERIOD_SLEEP = 3;

    // Units
    public static final Unit<Temperature> FAHRENHEIT = ImperialUnits.FAHRENHEIT;
    public static final Unit<Dimensionless> PERCENT = Units.PERCENT;

    // API Keys (from Python implementation)
    public static final String API_KEY_LOCATION_ID = "LocationID";
    public static final String API_KEY_DEVICE_ID = "DeviceID";
    public static final String API_KEY_NAME = "Name";
    public static final String API_KEY_DEVICES = "Devices";
    public static final String API_KEY_GATEWAY = "Gateway";
    public static final String API_KEY_DEVICE_INFO = "DeviceInfo";
    public static final String API_KEY_UI_DATA = "UIData";
    public static final String API_KEY_FAN_STATUS = "fanStatus";
    public static final String API_KEY_SYSTEM_STATUS = "systemStatus";
    public static final String API_KEY_DEVICE_STATUS = "deviceStatus";
    public static final String API_KEY_INDOOR_TEMPERATURE = "indoorTemperature";
    public static final String API_KEY_OUTDOOR_TEMPERATURE = "outdoorTemperature";
    public static final String API_KEY_INDOOR_HUMIDITY = "indoorHumidity";
    public static final String API_KEY_OUTDOOR_HUMIDITY = "outdoorHumidity";
    public static final String API_KEY_HEAT_SETPOINT = "heatSetpoint";
    public static final String API_KEY_COOL_SETPOINT = "coolSetpoint";
    public static final String API_KEY_HOLD_STATUS = "holdStatus";
    public static final String API_KEY_SCHEDULE_STATUS = "scheduleStatus";
    public static final String API_KEY_SYSTEM_SWITCH = "systemSwitch";
    public static final String API_KEY_FAN_MODE = "fanMode";
    public static final String API_KEY_VACATION_HOLD = "vacationHold";
    public static final String API_KEY_VACATION_HOLD_UNTIL = "vacationHoldUntil";
    public static final String API_KEY_TEMPORARY_HOLD_UNTIL = "temporaryHoldUntil";
    public static final String API_KEY_STATUS_HEAT = "statusHeat";
    public static final String API_KEY_STATUS_COOL = "statusCool";
    public static final String API_KEY_CURRENT_SCHEDULE_PERIOD = "currentSchedulePeriod";
    public static final String API_KEY_NEXT_SCHEDULE_PERIOD = "nextSchedulePeriod";
    public static final String API_KEY_NEXT_SCHEDULE_TIME = "nextScheduleTime";
    public static final String API_KEY_ALERTS = "alerts";
    public static final String API_KEY_ALERTS_COUNT = "alertsCount";
    public static final String API_KEY_LAST_UPDATE = "lastUpdate";
    public static final String API_KEY_TEMPERATURE_UNIT = "temperatureUnit";
    public static final String API_KEY_DEADBAND = "deadband";

    // Response Keys
    public static final String RESPONSE_SUCCESS = "success";
    public static final String RESPONSE_SUCCESS_VALUE = "1";
    public static final String RESPONSE_ERROR = "error";
    public static final String RESPONSE_MESSAGE = "message";
    public static final String RESPONSE_DATA = "data";
    public static final String RESPONSE_LOCATIONS = "locations";
    public static final String RESPONSE_DEVICES = "devices";

    // Login and Session
    public static final String LOGIN_REMEMBER_ME_VALUE = "false";
    public static final String LOGIN_TIME_OFFSET_VALUE = "480";

    // Request Parameters
    public static final String PARAM_PAGE = "page";
    public static final String PARAM_FILTER = "filter";
    public static final String PARAM_PAGE_VALUE = "1";
    public static final String PARAM_FILTER_VALUE = "";

    // Thermostat Settings Default Values
    public static final String SETTING_HEAT_NEXT_PERIOD = "HeatNextPeriod";
    public static final String SETTING_COOL_NEXT_PERIOD = "CoolNextPeriod";
}
