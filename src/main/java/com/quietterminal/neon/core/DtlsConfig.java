package com.quietterminal.neon.core;

import javax.net.ssl.*;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * Factory methods for constructing {@link SSLContext} instances configured for
 * DTLS.
 *
 * <p>
 * The relay holds the server certificate and private key; hosts and clients
 * trust that
 * certificate. Typical setup:
 * <ul>
 * <li>Relay: {@link #fromKeyStore(KeyStore, char[])} — has certificate +
 * key</li>
 * <li>Host / Client: {@link #withTrustStore(KeyStore)} — trusts the relay
 * certificate</li>
 * <li>Development / testing: {@link #insecureTrustAll()} — accepts any
 * certificate</li>
 * </ul>
 *
 * <p>
 * Pass the returned {@link SSLContext} to
 * {@link NeonConfig.Builder#sslContext(SSLContext)}.
 * When {@code sslContext} is set on a {@link NeonConfig}, all components using
 * that config will
 * communicate over DTLS.
 */
public final class DtlsConfig {

    private DtlsConfig() {
    }

    /**
     * Creates a server-side {@link SSLContext} backed by the given key store.
     * Use this on the relay, which holds the server certificate and private key.
     *
     * @param keyStore    key store containing the certificate chain and private key
     * @param keyPassword password protecting the private key entry
     * @return configured DTLS {@link SSLContext}
     * @throws Exception if the context cannot be initialised
     */
    public static SSLContext fromKeyStore(KeyStore keyStore, char[] keyPassword) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keyPassword);
        SSLContext ctx = SSLContext.getInstance("DTLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx;
    }

    /**
     * Creates a client-side {@link SSLContext} that trusts certificates in the
     * given trust store.
     * Use this on hosts and clients connecting to a relay whose certificate is in
     * {@code trustStore}.
     *
     * @param trustStore key store containing the trusted relay certificate(s)
     * @return configured DTLS {@link SSLContext}
     * @throws Exception if the context cannot be initialised
     */
    public static SSLContext withTrustStore(KeyStore trustStore) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        SSLContext ctx = SSLContext.getInstance("DTLS");
        ctx.init(null, tmf.getTrustManagers(), new SecureRandom());
        return ctx;
    }

    /**
     * Creates a {@link SSLContext} that accepts any server certificate without
     * validation.
     *
     * <p>
     * <strong>Never use this in production.</strong> For local development and
     * integration
     * tests only.
     *
     * @return an insecure DTLS {@link SSLContext}
     * @throws Exception if the context cannot be initialised
     */
    public static SSLContext insecureTrustAll() throws Exception {
        TrustManager[] trustAll = { new X509ExtendedTrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String t, java.net.Socket s) {
            }

            public void checkServerTrusted(X509Certificate[] c, String t, java.net.Socket s) {
            }

            public void checkClientTrusted(X509Certificate[] c, String t, SSLEngine e) {
            }

            public void checkServerTrusted(X509Certificate[] c, String t, SSLEngine e) {
            }

            public void checkClientTrusted(X509Certificate[] c, String t) {
            }

            public void checkServerTrusted(X509Certificate[] c, String t) {
            }

            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        } };
        SSLContext ctx = SSLContext.getInstance("DTLS");
        ctx.init(null, trustAll, new SecureRandom());
        return ctx;
    }
}
