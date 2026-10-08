package museon_online.astor_butler.model;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GigaChatTrustTest {

    private static final Path TEST_CA = Path.of("src/test/resources/certs/test-root-ca.pem");

    @Test
    void thePemCertificateIsTrustedNextToTheJvmDefaultsNotInsteadOfThem() throws Exception {
        List<X509Certificate> certificates = GigaChatTrust.readCertificates(TEST_CA);
        assertThat(certificates).hasSize(1);
        X509Certificate testRoot = certificates.getFirst();
        assertThat(testRoot.getSubjectX500Principal().getName()).contains("Astor Test Root CA");

        SSLContext context = GigaChatTrust.sslContext(TEST_CA);
        X509TrustManager composite = compositeOf(TEST_CA);
        TrustManagerFactory defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        defaults.init((KeyStore) null);
        X509TrustManager jvm = (X509TrustManager) defaults.getTrustManagers()[0];

        assertThat(context.getProtocol()).isEqualTo("TLS");
        assertThat(composite.getAcceptedIssuers()).contains(testRoot);
        assertThat(composite.getAcceptedIssuers().length).isEqualTo(jvm.getAcceptedIssuers().length + 1);
        assertThat(jvm.getAcceptedIssuers()).doesNotContain(testRoot);
        // The self-signed test root verifies as its own chain through the PEM side only.
        composite.checkServerTrusted(new X509Certificate[]{testRoot}, "RSA");
        assertThatThrownBy(() -> jvm.checkServerTrusted(new X509Certificate[]{testRoot}, "RSA"))
                .isInstanceOf(CertificateException.class);
    }

    @Test
    void aPemWithoutCertificatesIsRejected() throws Exception {
        Path empty = Files.createTempFile("gigachat-empty", ".pem");
        try {
            Files.writeString(empty, "not a certificate\n");
            assertThatThrownBy(() -> GigaChatTrust.sslContext(empty)).isInstanceOf(CertificateException.class);
        } finally {
            Files.deleteIfExists(empty);
        }
    }

    private static X509TrustManager compositeOf(Path pem) throws Exception {
        List<X509Certificate> extra = GigaChatTrust.readCertificates(pem);
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("test", extra.getFirst());
        TrustManagerFactory extraFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        extraFactory.init(store);
        TrustManagerFactory defaultFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        defaultFactory.init((KeyStore) null);
        return new GigaChatTrust.CompositeTrustManager(
                (X509TrustManager) defaultFactory.getTrustManagers()[0],
                (X509TrustManager) extraFactory.getTrustManagers()[0]);
    }
}
