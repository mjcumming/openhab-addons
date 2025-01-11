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
package org.openhab.binding.linkplay.internal.http;

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
 * Utility class for SSL/TLS configuration in LinkPlay binding
 *
 * @author Mark Theunissen - Initial contribution
 */
@NonNullByDefault
public class LinkPlaySslUtil {
    private static final Logger logger = LoggerFactory.getLogger(LinkPlaySslUtil.class);

    private static final String BEGIN_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----";
    private static final String END_PRIVATE_KEY = "-----END PRIVATE KEY-----";
    private static final String BEGIN_CERTIFICATE = "-----BEGIN CERTIFICATE-----";
    private static final String END_CERTIFICATE = "-----END CERTIFICATE-----";

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

    public static X509TrustManager createLearningTrustManager(boolean trustAll,
            @Nullable X509TrustManager baseTrustManager) {
        if (trustAll) {
            return createTrustAllManager();
        }
        return baseTrustManager != null ? baseTrustManager : createTrustManager();
    }

    public static X509TrustManager createTrustManager() {
        try {
            // Create a KeyStore containing our trusted CAs
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);

            // Extract certificate from PEM content
            String certPem = extractCertificate(LinkPlayPemConstants.PEM_CONTENT);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf
                    .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(certPem)));

            keyStore.setCertificateEntry("linkplay-cert", cert);

            // Create a TrustManager that trusts the certificate in our KeyStore
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            return (X509TrustManager) tmf.getTrustManagers()[0];
        } catch (Exception e) {
            logger.warn("Error creating trust manager: {}", e.getMessage());
            throw new IllegalStateException("Failed to create trust manager", e);
        }
    }

    public static SSLContext createSslContext(X509TrustManager trustManager) {
        try {
            // Create KeyStore with private key and certificate
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);

            // Extract and decode private key
            String privateKeyPem = extractPrivateKey(LinkPlayPemConstants.PEM_CONTENT);
            byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyPem);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

            // Extract and decode certificate
            String certPem = extractCertificate(LinkPlayPemConstants.PEM_CONTENT);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf
                    .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(certPem)));

            // Store private key and certificate in KeyStore
            keyStore.setKeyEntry("linkplay", privateKey, new char[0], new X509Certificate[] { cert });

            // Create KeyManager for client authentication
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, new char[0]);
            KeyManager[] keyManagers = kmf.getKeyManagers();

            // Create SSL context with both KeyManager and TrustManager
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagers, new TrustManager[] { trustManager }, null);
            return sslContext;
        } catch (Exception e) {
            logger.warn("Error creating SSL context: {}", e.getMessage());
            throw new IllegalStateException("Failed to create SSL context", e);
        }
    }

    public static HttpClient createHttpsClient(SSLContext sslContext) {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        sslContextFactory.setSslContext(sslContext);
        sslContextFactory.setEndpointIdentificationAlgorithm(null); // Disable hostname verification

        HttpClient httpClient = new HttpClient(sslContextFactory);
        try {
            httpClient.start();
            return httpClient;
        } catch (Exception e) {
            logger.warn("Error starting HTTPS client: {}", e.getMessage());
            throw new IllegalStateException("Failed to start HTTPS client", e);
        }
    }

    public static String extractPrivateKey(String pemContent) {
        int startIndex = pemContent.indexOf(BEGIN_PRIVATE_KEY) + BEGIN_PRIVATE_KEY.length();
        int endIndex = pemContent.indexOf(END_PRIVATE_KEY);
        return pemContent.substring(startIndex, endIndex).replaceAll("\\s+", "");
    }

    public static String extractCertificate(String pemContent) {
        int startIndex = pemContent.indexOf(BEGIN_CERTIFICATE) + BEGIN_CERTIFICATE.length();
        int endIndex = pemContent.indexOf(END_CERTIFICATE);
        return pemContent.substring(startIndex, endIndex).replaceAll("\\s+", "");
    }
}
