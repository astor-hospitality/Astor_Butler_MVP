package museon_online.astor_butler.model;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * TLS trust for Sber endpoints. GigaChat, SaluteSpeech and the OAuth gateway present certificates issued by the
 * Russian Trusted Root CA (Минцифры), which the JVM's default trust store does not contain.
 *
 * <p>The PEM file (root and, if the operator bundles it, the sub CA) is added to the default trust
 * anchors: a server certificate is accepted when either the JVM store or the PEM file vouches for it.
 * Verification is never disabled, and the host name check stays with the JDK HTTP client.
 */
public final class GigaChatTrust {

    private GigaChatTrust() {
    }

    /** The JVM default trust, plus every certificate found in {@code pemPath}. */
    public static SSLContext sslContext(Path pemPath) throws GeneralSecurityException, IOException {
        List<X509Certificate> extra = readCertificates(pemPath);
        if (extra.isEmpty()) {
            throw new CertificateException("No X.509 certificates found in " + pemPath);
        }
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        int index = 0;
        for (X509Certificate certificate : extra) {
            store.setCertificateEntry("gigachat-ca-" + index++, certificate);
        }
        TrustManagerFactory extraFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        extraFactory.init(store);
        TrustManagerFactory defaultFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        defaultFactory.init((KeyStore) null);

        X509TrustManager composite = new CompositeTrustManager(
                firstX509(defaultFactory.getTrustManagers()),
                firstX509(extraFactory.getTrustManagers())
        );
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{composite}, null);
        return context;
    }

    public static List<X509Certificate> readCertificates(Path pemPath) throws GeneralSecurityException, IOException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        try (InputStream in = Files.newInputStream(pemPath)) {
            Collection<? extends java.security.cert.Certificate> parsed = factory.generateCertificates(in);
            List<X509Certificate> certificates = new ArrayList<>();
            for (java.security.cert.Certificate certificate : parsed) {
                if (certificate instanceof X509Certificate x509) {
                    certificates.add(x509);
                }
            }
            return certificates;
        }
    }

    private static X509TrustManager firstX509(TrustManager[] managers) {
        for (TrustManager manager : managers) {
            if (manager instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new IllegalStateException("No X509TrustManager available");
    }

    /** Accepts a chain that either trust manager accepts; rejects it only when both reject it. */
    static final class CompositeTrustManager implements X509TrustManager {

        private final X509TrustManager primary;
        private final X509TrustManager secondary;

        CompositeTrustManager(X509TrustManager primary, X509TrustManager secondary) {
            this.primary = Objects.requireNonNull(primary);
            this.secondary = Objects.requireNonNull(secondary);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                primary.checkClientTrusted(chain, authType);
            } catch (CertificateException primaryFailure) {
                try {
                    secondary.checkClientTrusted(chain, authType);
                } catch (CertificateException secondaryFailure) {
                    secondaryFailure.addSuppressed(primaryFailure);
                    throw secondaryFailure;
                }
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                primary.checkServerTrusted(chain, authType);
            } catch (CertificateException primaryFailure) {
                try {
                    secondary.checkServerTrusted(chain, authType);
                } catch (CertificateException secondaryFailure) {
                    secondaryFailure.addSuppressed(primaryFailure);
                    throw secondaryFailure;
                }
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] first = primary.getAcceptedIssuers();
            X509Certificate[] second = secondary.getAcceptedIssuers();
            X509Certificate[] all = new X509Certificate[first.length + second.length];
            System.arraycopy(first, 0, all, 0, first.length);
            System.arraycopy(second, 0, all, first.length, second.length);
            return all;
        }
    }
}
