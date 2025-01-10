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
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Provides the PEM content and SSL context creation for LinkPlay devices.
 *
 * @author Michael Cumming - Initial contribution
 */
@NonNullByDefault
public class LinkPlayPemConstants {

    // Embedded PEM content
    public static final String PEM_CONTENT = """
            -----BEGIN PRIVATE KEY-----
            MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCk/u2tH0LwCOv8
            JmqLvQdjNAdkxfSCPwrdHM7STlq5xaJGQe29yd8kjP7h0wERkeO/9JO62wUHBu0P
            WIsS/jwLG+G3oAU+7BNfjmBhDXyHIRQLzAWEbtxbsSTke1losfvlQXGumXrMqf9X
            LdIYvbA53mp8GImQbJkaCDvwnEdUFkuJ0W5Tofr58jJqfCt6OPwHmnP4oC6LpPtJ
            YDy7r1Q9sLgCYEtDEw/Mhf+mKuC0pnst52e1qceVjvCuUBoeuhk6kpnEbpSdEKbD
            bdE8cPoVRmrj1//PLFMVtNB7k2aPMb3CcoJ/dHxaCXwk9b3jIBs6CyWixN92CuaO
            Q98Ug/YlAgMBAAECggEAHyCpHlwjeL12J9/nge1rk1+hdXWTJ29VUVm5+xslKp8K
            ek6912xaWL7w5xGzxejMGs69gCcJz8WSu65srmygT0g3UTkzRCetj/2AWU7+C1BG
            Q+N9tvpjQDkvSJusxn+tkhbCp7n03N/FeGEAngJLWN+JH1hRu5mBWNPs2vvgyRAO
            Cv95G7uENavCUXcyYsKPoAfz3ebD/idwwWW2RKAd0ufYeafiFC0ImTLcpEjBvCTW
            UoAniBSVx1PHK4IAUb3pMdPtIv1uBlIMotHS/GdEyHU6qOsX5ijHqncHHneaytmL
            +wJukPqASEBl3F2UnzryBUgGqr1wyH9vtPGjklnngQKBgQDZv3oxZWul//2LV+jo
            ZipbnP6nwG3J6pOWPDD3dHoZ6Q2DRyJXN5ty40PS393GVvrSJSdRGeD9+ox5sFoj
            iUMgd6kHG4ME7Fre57zUkqy1Ln1K1fkP5tBUD0hviigHBWih2/Nyl2vrdvX5Wpxx
            5r42UQa9nOzrNB03DTOhDrUszQKBgQDB+xdMRNSFfCatQj+y2KehcH9kaANPvT0l
            l9vgb72qks01h05GSPBZnT1qfndh/Myno9KuVPhJ0HrVwRAjZTd4T69fAH3imW+R
            7HP+RgDen4SRTxj6UTJh2KZ8fdPeCby1xTwxYNjq8HqpiO6FHZpE+l4FE8FalZK+
            Z3GhE7DuuQKBgDq7b+0U6xVKWAwWuSa+L9yoGvQKblKRKB/Uumx0iV6lwtRPAo89
            23sAm9GsOnh+C4dVKCay8UHwK6XDEH0XT/jY7cmR/SP90IDhRsibi2QPVxIxZs2I
            N1cFDEexnxxNtCw8VIzrFNvdKXmJnDsIvvONpWDNjAXg96RatjtR6UJdAoGBAIAx
            HU5r1j54s16gf1QD1ZPcsnN6QWX622PynX4OmjsVVMPhLRtJrHysax/rf52j4OOQ
            YfSPdp3hRqvoMHATvbqmfnC79HVBjPfUWTtaq8xzgro8mXcjHbaH5E41IUSFDs7Z
            D1Raej+YuJc9RNN3orGe+29DhO4GFrn5xp/6UV0RAoGBAKUdRgryWzaN4auzWaRD
            lxoMhlwQdCXzBI1YLH2QUL8elJOHMNfmja5G9iW07ZrhhvQBGNDXFbFrX4hI3c/0
            JC3SPhaaedIjOe9Qd3tn5KgYxbBnWnCTt0kxgro+OM3ORgJseSWbKdRrjOkUxkab
            /NDvel7IF63U4UEkrVVt1bYg
            -----END PRIVATE KEY-----
            -----BEGIN CERTIFICATE-----
            MIIDmDCCAoACAQEwDQYJKoZIhvcNAQELBQAwgZExCzAJBgNVBAYTAkNOMREwDwYD
            VQQIDAhTaGFuZ2hhaTERMA8GA1UEBwwIU2hhbmdoYWkxETAPBgNVBAoMCExpbmtw
            bGF5MQwwCgYDVQQLDANpbmMxGTAXBgNVBAMMEHd3dy5saW5rcGxheS5jb20xIDAe
            BgkqhkiG9w0BCQEWEW1haWxAbGlua3BsYXkuY29tMB4XDTE4MTExNTAzMzI1OVoX
            DTQ2MDQwMTAzMzI1OVowgZExCzAJBgNVBAYTAkNOMREwDwYDVQQIDAhTaGFuZ2hh
            aTERMA8GA1UEBwwIU2hhbmdoYWkxETAPBgNVBAoMCExpbmtwbGF5MQwwCgYDVQQL
            DANpbmMxGTAXBgNVBAMMEHd3dy5saW5rcGxheS5jb20xIDAeBgkqhkiG9w0BCQEW
            EW1haWxAbGlua3BsYXkuY29tMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKC
            AQEApP7trR9C8Ajr/CZqi70HYzQHZMX0gj8K3RzO0k5aucWiRkHtvcnfJIz+4dMB
            EZHjv/STutsFBwbtD1iLEv48Cxvht6AFPuwTX45gYQ18hyEUC8wFhG7cW7Ek5HtZ
            aLH75UFxrpl6zKn/Vy3SGL2wOd5qfBiJkGyZGgg78JxHVBZLidFuU6H6+fIyanwr
            ejj8B5pz+KAui6T7SWA8u69UPbC4AmBLQxMPzIX/pirgtKZ7LedntanHlY7wrlAa
            HroZOpKZxG6UnRCmw23RPHD6FUZq49f/zyxTFbTQe5NmjzG9wnKCf3R8Wgl8JPW9
            4yAbOgslosTfdgrmjkPfFIP2JQIDAQABMA0GCSqGSIb3DQEBCwUAA4IBAQARmy6f
            esrifhW5NM9i3xsEVp945iSXhqHgrtIROgrC7F1EIAyoIiBdaOvitZVtsYc7Ivys
            QtyVmEGscyjuYTdfigvwTVVj2oCeFv1Xjf+t/kSuk6X3XYzaxPPnFG4nAe2VwghE
            rbZG0K5l8iXM7Lm+ZdqQaAYVWsQDBG8lbczgkB9q5ed4zbDPf6Fsrsynxji/+xa4
            9ARfyHlkCDBThGNnnl+QITtfOWxm/+eReILUQjhwX+UwbY07q/nUxLlK6yrzyjnn
            wi2B2GovofQ/4icVZ3ecTqYK3q9gEtJi72V+dVHM9kSA4Upy28Y0U1v56uoqeWQ6
            uc2m8y8O/hXPSfKd
            -----END CERTIFICATE-----
                   """;

    /**
     * Creates an SSLContext configured with the embedded certificate and private key.
     *
     * @return Configured SSLContext
     * @throws Exception if there's an error creating the SSLContext
     */
    public static SSLContext createSslContext() throws Exception {
        // Split PEM content into private key and certificate parts
        String[] parts = PEM_CONTENT.split("-----END PRIVATE KEY-----");
        String privateKeyPem = parts[0].replace("-----BEGIN PRIVATE KEY-----", "").trim();
        String certificatePem = parts[1].replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "").trim();

        // Create private key
        byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyPem);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

        // Create certificate
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        Certificate certificate = certFactory
                .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(certificatePem)));

        // Create KeyStore and initialize it
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setKeyEntry("linkplay", privateKey, null, new Certificate[] { certificate });

        // Initialize KeyManagerFactory
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, null);

        // Initialize TrustManagerFactory
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);

        // Create and initialize SSLContext
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        return sslContext;
    }

    private LinkPlayPemConstants() {
        // Prevent instantiation
    }
}
