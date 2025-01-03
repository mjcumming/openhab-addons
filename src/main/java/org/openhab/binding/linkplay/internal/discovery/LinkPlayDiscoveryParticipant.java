
package org.openhab.binding.linkplay.internal.discovery;

import static org.openhab.binding.linkplay.internal.LinkPlayBindingConstants.*;

import java.net.InetAddress;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.jmdns.ServiceInfo;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable; 
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.mdns.MDNSDiscoveryParticipant;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@NonNullByDefault
@Component(service = MDNSDiscoveryParticipant.class)
public class LinkPlayDiscoveryParticipant implements MDNSDiscoveryParticipant {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayDiscoveryParticipant.class);
    private static final String SERVICE_TYPE = "_linkplay._tcp.local.";
    private static final int DISCOVERY_TIMEOUT_SEC = 30;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;

    @Override
    public Set<ThingTypeUID> getSupportedThingTypeUIDs() {
        return SUPPORTED_THING_TYPES_UIDS;
    }

    @Override
    public String getServiceType() {
        return SERVICE_TYPE;
    }

    @Override
    public @Nullable DiscoveryResult createResult(ServiceInfo service) {
        if (!validateService(service)) {
            return null;
        }

        String deviceId = getDeviceId(service);
        if (deviceId == null) {
            return null;
        }

        String ipAddress = getIpAddress(service);
        if (ipAddress == null) {
            return null;
        }

        String model = service.getPropertyString("md");
        String name = service.getName();
        String firmware = service.getPropertyString("fw");

        logger.debug("LinkPlay discovery - Device: {}, IP: {}, Model: {}, Firmware: {}", 
            name, ipAddress, model, firmware);

        ThingUID uid = new ThingUID(THING_TYPE_DEVICE, deviceId);

        return DiscoveryResultBuilder.create(uid)
                .withLabel(name != null ? name : "LinkPlay Device")
                .withRepresentationProperty(PROPERTY_DEVICE_ID)
                .withProperty(PROPERTY_IP_ADDRESS, ipAddress)
                .withProperty(PROPERTY_DEVICE_ID, deviceId)
                .withProperty(PROPERTY_MODEL, model)
                .withProperty(PROPERTY_FIRMWARE, firmware)
                .withTimeToLive(TimeUnit.SECONDS.toSeconds(DISCOVERY_TIMEOUT_SEC))
                .build();
    }

    @Override
    public @Nullable ThingUID getThingUID(ServiceInfo service) {
        if (!validateService(service)) {
            return null;
        }

        String deviceId = getDeviceId(service);
        if (deviceId == null) {
            return null;
        }

        return new ThingUID(THING_TYPE_DEVICE, deviceId);
    }

    private boolean validateService(ServiceInfo service) {
        if (service == null || !service.hasData()) {
            logger.debug("LinkPlay device found but no valid service data");
            return false;
        }
        return true;
    }

    private @Nullable String getDeviceId(ServiceInfo service) {
        String deviceId = service.getPropertyString("id");
        if (deviceId == null || deviceId.isEmpty()) {
            logger.debug("LinkPlay device found but no device ID available: {}", service.getName());
            return null;
        }
        return deviceId;
    }

    private @Nullable String getIpAddress(ServiceInfo service) {
        InetAddress[] addresses = service.getInet4Addresses();
        if (addresses == null || addresses.length == 0) {
            logger.debug("LinkPlay device found but no IPv4 address available: {}", service.getName());
            return null;
        }
        
        String ipAddress = null;
        int retries = 0;
        
        while (ipAddress == null && retries < MAX_RETRIES) {
            try {
                ipAddress = addresses[0].getHostAddress();
                if (ipAddress == null || ipAddress.isEmpty()) {
                    throw new IllegalStateException("Empty IP address");
                }
            } catch (Exception e) {
                logger.debug("Failed to get IP address, attempt {}: {}", retries + 1, e.getMessage());
                retries++;
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        return ipAddress;
    }
}
