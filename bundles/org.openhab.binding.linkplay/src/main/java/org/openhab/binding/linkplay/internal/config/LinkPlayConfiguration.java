@NonNullByDefault
public class LinkPlayConfiguration {

    /**
     * Minimum refresh interval in seconds
     */
    public static final int MIN_REFRESH_INTERVAL = 10;

    /**
     * IP address of the LinkPlay device
     */
    public String ipAddress = "";

    /**
     * Name of the LinkPlay device
     */
    public String deviceName = "";

    /**
     * UDN (Unique Device Name) of the LinkPlay device
     */
    public String udn = "";

    /**
     * Polling interval in seconds for polling the device state.
     * Default value is 30 seconds.
     */
    public int pollingInterval = 30;

    public static final int MIN_POLLING_INTERVAL = 10;
    public static final int MAX_POLLING_INTERVAL = 60;

    /**
     * Maximum number of retries for HTTP commands.
     * Default value is 3.
     */
    public int maxRetries = 3;

    /**
     * Delay in milliseconds between retries for HTTP commands.
     * Default value is 1000 ms.
     */
    public int retryDelayMillis = 1000;

    public boolean isValid() {
        // IP address is required
        if (ipAddress.isEmpty()) {
            return false;
        }

        // Validate IP address format
        String[] octets = ipAddress.split("\\.");
        if (octets.length != 4) {
            return false;
        }

        try {
            for (String octet : octets) {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) {
                    return false;
                }
            }
        } catch (NumberFormatException e) {
            return false;
        }

        // Normalize polling interval
        pollingInterval = Math.min(Math.max(pollingInterval, MIN_POLLING_INTERVAL), MAX_POLLING_INTERVAL);

        // Normalize UDN if present
        if (!udn.isEmpty() && !udn.startsWith("uuid:")) {
            udn = "uuid:" + udn;
        }

        // Ensure maxRetries and retryDelayMillis have sensible values
        maxRetries = Math.max(maxRetries, 0); // Minimum 0 retries allowed
        retryDelayMillis = Math.max(retryDelayMillis, 100); // Minimum 100ms delay

        return true;
    }

    public String getUdn() {
        return udn;
    }
}
