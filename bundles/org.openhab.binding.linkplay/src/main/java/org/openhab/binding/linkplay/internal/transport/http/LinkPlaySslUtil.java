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
 * The {@link LinkPlaySslUtil} class provides SSL/TLS configuration utilities for the LinkPlay binding.
 * <p>
 * This utility class handles:
 * <ul>
 * <li>Creation of trust managers for server certificate validation</li>
 * <li>Setup of client certificates for mutual TLS authentication</li>
 * <li>Configuration of HTTPS clients for secure communication with LinkPlay devices</li>
 * </ul>
 * <p>
 * The class supports both standard certificate validation and a trust-all mode for development/testing.
 * It manages embedded certificates and private keys stored in PEM format for client authentication.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlaySslUtil {

    private static final Logger logger = LoggerFactory.getLogger(LinkPlaySslUtil.class);

    // PEM section markers for certificate and key extraction
    private static final String BEGIN_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----";
    private static final String END_PRIVATE_KEY = "-----END PRIVATE KEY-----";
    private static final String BEGIN_CERTIFICATE = "-----BEGIN CERTIFICATE-----";
    private static final String END_CERTIFICATE = "-----END CERTIFICATE-----";

    private LinkPlaySslUtil() {
        // Prevent instantiation of utility class
    }

    /**
     * Creates an X.509 trust manager that accepts all server certificates.
     * <p>
     * WARNING: This trust manager bypasses all certificate validation and should only be used
     * in controlled environments where security is not a primary concern.
     *
     * @return An {@link X509TrustManager} that trusts all certificates
     */
    public static X509TrustManager createTrustAllManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate @Nullable [] chain, @Nullable String authType) {
                // Trust all clients
            }

            @Override
            public void checkServerTrusted(X509Certificate @Nullable [] chain, @Nullable String authType) {
                // Trust all servers
            }

            @Override
            public X509Certificate @Nullable [] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    /**
     * Creates an appropriate X.509 trust manager based on configuration settings.
     *
     * @param trustAll true to create a trust-all manager, false for certificate validation
     * @param baseTrustManager optional base trust manager to use if provided
     * @return An {@link X509TrustManager} configured according to the parameters
     */
    public static X509TrustManager createLearningTrustManager(boolean trustAll,
            @Nullable X509TrustManager baseTrustManager) {
        if (trustAll) {
            logger.debug("Creating trust-all manager as requested");
            return createTrustAllManager();
        }
        return baseTrustManager != null ? baseTrustManager : createTrustManager();
    }

    /**
     * Creates a trust manager using the embedded LinkPlay certificate.
     *
     * @return An {@link X509TrustManager} initialized with the embedded certificate
     * @throws IllegalStateException if the trust manager creation fails
     */
    public static X509TrustManager createTrustManager() {
        try {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);

            String certPem = extractCertificate(PemConstants.PEM_CONTENT);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf
                    .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(certPem)));

            keyStore.setCertificateEntry("linkplay-cert", cert);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            return (X509TrustManager) tmf.getTrustManagers()[0];
        } catch (Exception e) {
            logger.warn("Error creating trust manager: {}", e.getMessage());
            throw new IllegalStateException("Failed to create trust manager", e);
        }
    }

    /**
     * Creates an SSL context configured for mutual TLS authentication.
     * <p>
     * This context includes both the trust manager for server verification and
     * the client certificate/private key pair for client authentication.
     *
     * @param trustManager the trust manager to use for server certificate validation
     * @return A configured {@link SSLContext}
     * @throws IllegalStateException if the SSL context creation fails
     */
    public static SSLContext createSslContext(X509TrustManager trustManager) {
        try {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);

            // Extract and decode private key
            String privateKeyPem = extractPrivateKey(PemConstants.PEM_CONTENT);
            byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyPem);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

            // Extract and decode certificate
            String certPem = extractCertificate(PemConstants.PEM_CONTENT);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf
                    .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(certPem)));

            keyStore.setKeyEntry("linkplay", privateKey, new char[0], new X509Certificate[] { cert });

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, new char[0]);
            KeyManager[] keyManagers = kmf.getKeyManagers();

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagers, new TrustManager[] { trustManager }, null);
            logger.debug("SSL context created successfully with client certificate");
            return sslContext;
        } catch (Exception e) {
            logger.error("Error creating SSL context: {}", e.getMessage());
            throw new IllegalStateException("Failed to create SSL context", e);
        }
    }

    /**
     * Creates a pre-configured HTTPS client using the provided SSL context.
     * <p>
     * The client is configured to skip hostname verification since LinkPlay devices
     * often use self-signed certificates with mismatched hostnames.
     *
     * @param sslContext the SSL context to use for HTTPS connections
     * @return A configured {@link HttpClient}
     * @throws IllegalStateException if the client creation or startup fails
     */
    public static HttpClient createHttpsClient(SSLContext sslContext) {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        sslContextFactory.setSslContext(sslContext);
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
     * Extracts the private key from a PEM-formatted string.
     *
     * @param pemContent the complete PEM content containing the private key
     * @return The base64-encoded private key without PEM headers/footers
     */
    public static String extractPrivateKey(String pemContent) {
        int startIndex = pemContent.indexOf(BEGIN_PRIVATE_KEY) + BEGIN_PRIVATE_KEY.length();
        int endIndex = pemContent.indexOf(END_PRIVATE_KEY);
        return pemContent.substring(startIndex, endIndex).replaceAll("\\s+", "");
    }

    /**
     * Extracts the certificate from a PEM-formatted string.
     *
     * @param pemContent the complete PEM content containing the certificate
     * @return The base64-encoded certificate without PEM headers/footers
     */
    public static String extractCertificate(String pemContent) {
        int startIndex = pemContent.indexOf(BEGIN_CERTIFICATE) + BEGIN_CERTIFICATE.length();
        int endIndex = pemContent.indexOf(END_CERTIFICATE);
        return pemContent.substring(startIndex, endIndex).replaceAll("\\s+", "");
    }
}
