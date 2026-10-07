package net.pilgrim.sip.security;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import net.pilgrim.sip.config.SipServerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;

/**
 * Factory creating Netty {@link SslContext} instances for SIP over TLS (RFC 3261 SIPS / RFC 5630).
 */
public final class SipSslContextFactory {

    private static final Logger LOG = LoggerFactory.getLogger(SipSslContextFactory.class);

    private SipSslContextFactory() {}

    /**
     * Builds a server {@link SslContext} based on configuration.
     * If no keystore is configured, falls back to a self-signed certificate for development and testing.
     */
    public static SslContext createServerSslContext(SipServerConfiguration config) {
        try {
            SslContextBuilder builder;

            if (config.getKeyStorePath() != null && !config.getKeyStorePath().isBlank()) {
                KeyStore keyStore = loadKeyStore(
                        config.getKeyStorePath(),
                        config.getKeyStorePassword(),
                        config.getKeyStoreType()
                );
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                char[] password = config.getKeyStorePassword() != null ? config.getKeyStorePassword().toCharArray() : new char[0];
                kmf.init(keyStore, password);
                builder = SslContextBuilder.forServer(kmf);
            } else {
                LOG.info("No TLS keyStorePath configured; generating ephemeral SelfSignedCertificate for SIP TLS server.");
                SelfSignedCertificate ssc = new SelfSignedCertificate("localhost");
                builder = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey());
            }

            if (config.isClientAuth()) {
                builder.clientAuth(ClientAuth.REQUIRE);
            } else {
                builder.clientAuth(ClientAuth.NONE);
            }

            if (config.getTrustStorePath() != null && !config.getTrustStorePath().isBlank()) {
                KeyStore trustStore = loadKeyStore(
                        config.getTrustStorePath(),
                        config.getTrustStorePassword(),
                        config.getTrustStoreType()
                );
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);
                builder.trustManager(tmf);
            } else if (config.isTrustAll()) {
                builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            }

            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SIP server SSL context: " + e.getMessage(), e);
        }
    }

    /**
     * Builds a client {@link SslContext} based on configuration.
     */
    public static SslContext createClientSslContext(SipServerConfiguration config) {
        try {
            SslContextBuilder builder = SslContextBuilder.forClient();

            if (config != null && config.isTrustAll()) {
                builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            } else if (config != null && config.getTrustStorePath() != null && !config.getTrustStorePath().isBlank()) {
                KeyStore trustStore = loadKeyStore(
                        config.getTrustStorePath(),
                        config.getTrustStorePassword(),
                        config.getTrustStoreType()
                );
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);
                builder.trustManager(tmf);
            } else {
                // Default to trustAll if running tests or development without configured truststore
                builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            }

            if (config != null && config.getKeyStorePath() != null && !config.getKeyStorePath().isBlank()) {
                KeyStore keyStore = loadKeyStore(
                        config.getKeyStorePath(),
                        config.getKeyStorePassword(),
                        config.getKeyStoreType()
                );
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                char[] password = config.getKeyStorePassword() != null ? config.getKeyStorePassword().toCharArray() : new char[0];
                kmf.init(keyStore, password);
                builder.keyManager(kmf);
            }

            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SIP client SSL context: " + e.getMessage(), e);
        }
    }

    private static KeyStore loadKeyStore(String path, String password, String type) throws Exception {
        String storeType = (type != null && !type.isBlank()) ? type : "PKCS12";
        KeyStore ks = KeyStore.getInstance(storeType);
        char[] pwd = (password != null) ? password.toCharArray() : new char[0];

        File file = new File(path);
        InputStream rawStream = file.exists()
                ? new FileInputStream(file)
                : Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
        if (rawStream == null) {
            throw new IllegalArgumentException("Keystore not found at file or classpath: " + path);
        }
        try (InputStream is = rawStream) {
            ks.load(is, pwd);
        }
        return ks;
    }
}
