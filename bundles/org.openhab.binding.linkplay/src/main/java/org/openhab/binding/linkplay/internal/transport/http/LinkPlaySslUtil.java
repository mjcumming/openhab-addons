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

package org.openhab.binding.linkplay.internal.transport.http;

import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for SSL/TLS configuration in the LinkPlay binding.
 * <p>
 * Responsibilities:
 * - Create custom TrustManagers (trust-all or using an embedded cert).
 * - Create an SSLContext that includes our private key and certificate.
 * - Provide a specialized HttpClient for HTTPS communication with LinkPlay.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlaySslUtil {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlaySslUtil.class);

    // PEM section markers
    private static final String BEGIN_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----";
    private static final String END_PRIVATE_KEY = "-----END PRIVATE KEY-----";
    private static final String BEGIN_CERTIFICATE = "-----BEGIN CERTIFICATE-----";
    private static final String END_CERTIFICATE = "-----END CERTIFICATE-----";

    private LinkPlaySslUtil() {
        // Utility class - prevent instantiation
    }

    /**
     * Creates an X.509 trust manager that trusts ALL servers (dangerous, but often needed for custom device firmware).
     * Use with caution if security is a concern.
     */
    public static X509TrustManager createTrustAllManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate @Nullable [] chain, @Nullable String authType) {
                // No-op: trust all clients
            }

            @Override
            public void checkServerTrusted(X509Certificate @Nullable [] chain, @Nullable String authType) {
                // No-op: trust all servers
            }

            @Override
            public X509Certificate @Nullable [] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    /**
     * Creates an X.509 trust manager, either:
     * - trustAll = true => calls createTrustAllManager(), or
     * - baseTrustManager != null => uses it, or
     * - else => calls createTrustManager() to load the embedded LinkPlay cert from the PEM.
     */
    public static X509TrustManager createLearningTrustManager(boolean trustAll,
            @Nullable X509TrustManager baseTrustManager) {
        if (trustAll) {
            return createTrustAllManager();
        }
        return baseTrustManager != null ? baseTrustManager : createTrustManager();
    }

    /**
     * Creates a trust manager from the embedded certificate in {@link LinkPlayPemConstants#PEM_CONTENT}.
     * If the device uses the same cert for all firmware, this is sufficient.
     */
    public static X509TrustManager createTrustManager() {
        try {
            // Create a KeyStore containing our trusted CA/cert
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);

            // Extract the single certificate from our PEM blob
            String certPem = extractCertificate(LinkPlayPemConstants.PEM_CONTENT);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf
                    .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(certPem)));

            // Store it in the KeyStore
            keyStore.setCertificateEntry("linkplay-cert", cert);

            // Build a standard trust manager from that KeyStore
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            return (X509TrustManager) tmf.getTrustManagers()[0];
        } catch (Exception e) {
            logger.warn("Error creating trust manager: {}", e.getMessage());
            throw new IllegalStateException("Failed to create trust manager", e);
        }
    }

    /**
     * Creates an SSLContext that includes both a trust manager (for server verification)
     * and a client private key/cert from the embedded PEM. This is used if LinkPlay
     * devices require mutual TLS (client auth).
     */
    public static SSLContext createSslContext(X509TrustManager trustManager) {
        try {
            // Build a KeyStore with the private key and certificate
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);

            // Extract private key
            String privateKeyPem = extractPrivateKey(LinkPlayPemConstants.PEM_CONTENT);
            byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyPem);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

            // Extract certificate
            String certPem = extractCertificate(LinkPlayPemConstants.PEM_CONTENT);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf
                    .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(certPem)));

            // Store them in the KeyStore
            keyStore.setKeyEntry("linkplay", privateKey, new char[0], new X509Certificate[] { cert });

            // KeyManager for the client-side certificate
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, new char[0]);
            KeyManager[] keyManagers = kmf.getKeyManagers();

            // Initialize SSLContext with key & trust managers
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagers, new TrustManager[] { trustManager }, null);
            logger.info("SSL context successfully created with client certificate");
            return sslContext;
        } catch (Exception e) {
            logger.error("Error creating SSL context: {}", e.getMessage());
            throw new IllegalStateException("Failed to create SSL context", e);
        }
    }

    /**
     * Creates a Jetty HttpClient preconfigured with the given SSLContext (including custom trust or client cert).
     * Disables hostname verification since LinkPlay devices often present mismatched hostnames.
     */
    public static HttpClient createHttpsClient(SSLContext sslContext) {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        sslContextFactory.setSslContext(sslContext);
        // LinkPlay devices may not have a matching certificate hostname
        sslContextFactory.setEndpointIdentificationAlgorithm(null);

        HttpClient httpClient = new HttpClient(sslContextFactory);
        try {
            httpClient.start();
            return httpClient;
        } catch (Exception e) {
            logger.error("Error starting HTTPS client: {}", e.getMessage());
            throw new IllegalStateException("Failed to start HTTPS client", e);
        }
    }

    /**
     * Extracts the base64-encoded private key material from the combined PEM content,
     * removing the PEM headers and footers.
     */
    public static String extractPrivateKey(String pemContent) {
        int startIndex = pemContent.indexOf(BEGIN_PRIVATE_KEY) + BEGIN_PRIVATE_KEY.length();
        int endIndex = pemContent.indexOf(END_PRIVATE_KEY);
        return pemContent.substring(startIndex, endIndex).replaceAll("\\s+", "");
    }

    /**
     * Extracts the base64-encoded certificate material from the combined PEM content,
     * removing the PEM headers and footers.
     */
    public static String extractCertificate(String pemContent) {
        int startIndex = pemContent.indexOf(BEGIN_CERTIFICATE) + BEGIN_CERTIFICATE.length();
        int endIndex = pemContent.indexOf(END_CERTIFICATE);
        return pemContent.substring(startIndex, endIndex).replaceAll("\\s+", "");
    }
}
