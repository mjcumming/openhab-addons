package org.openhab.binding.linkplay.internal.http;

import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.io.net.http.SharedHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LinkPlayHttpClient} handles all HTTP interactions with the LinkPlay device.
 *
 * @author Michael Cumming
 */
@NonNullByDefault
public class LinkPlayHttpClient {

    private final Logger logger = LoggerFactory.getLogger(LinkPlayHttpClient.class);
    private final SharedHttpClient httpClient;
    private @Nullable String ipAddress;

    public LinkPlayHttpClient(SharedHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    /**
     * Sends an HTTP GET request to the device and returns the response asynchronously.
     *
     * @param command The API command to execute.
     * @return CompletableFuture containing the response string.
     */
    public CompletableFuture<String> sendCommand(String command) {
        if (ipAddress == null || ipAddress.isEmpty()) {
            logger.warn("IP address is not configured.");
            return CompletableFuture.failedFuture(new IllegalStateException("IP address is not configured."));
        }

        String url = "http://" + ipAddress + "/httpapi.asp?command=" + command;
        logger.debug("Sending command to LinkPlay device: {}", url);

        try {
            SSLContext sslContext = createSslContextFromPem(PemConstants.PEM_CONTENT);
            HttpClient client = HttpClient.newBuilder().sslContext(sslContext).build();

            return AsyncHttpClientUtil.sendAsyncGetRequest(client, url).thenApply(response -> {
                logger.debug("Received response: {}", response);
                return response;
            });
        } catch (Exception e) {
            logger.error("Error creating SSLContext or sending request: {}", e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    private SSLContext createSslContextFromPem(String pemContent) throws Exception {
        // Split PEM content
        String[] parts = pemContent.split("-----END PRIVATE KEY-----");
        String privateKeyContent = parts[0].replace("-----BEGIN PRIVATE KEY-----", "").trim();
        String certificateContent = parts[1].replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "").trim();

        // Decode private key
        byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyContent);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
        PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec);

        // Decode certificate
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        Certificate certificate = certFactory
                .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(certificateContent)));

        // Create KeyStore and initialize it
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setKeyEntry("linkplay", privateKey, null, new Certificate[] { certificate });

        // Initialize KeyManagerFactory with KeyStore
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, null);

        // Initialize TrustManagerFactory with KeyStore
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);

        // Create SSLContext
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        return sslContext;
    }
}
