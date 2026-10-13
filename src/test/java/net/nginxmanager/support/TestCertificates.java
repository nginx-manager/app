package net.nginxmanager.support;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 테스트용 자체 서명 인증서. 키는 PKCS#8(BEGIN PRIVATE KEY)로 낸다. */
public final class TestCertificates {

    private static final AtomicLong SERIAL = new AtomicLong(System.currentTimeMillis());

    public record Issued(String certPem, String keyPem, X509Certificate certificate) {
    }

    private TestCertificates() {
    }

    public static Issued selfSigned(List<String> domains, Instant notBefore, Instant notAfter) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(256);
            KeyPair keyPair = generator.generateKeyPair();
            X500Name subject = new X500Name("CN=" + domains.getFirst());
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject,
                    BigInteger.valueOf(SERIAL.incrementAndGet()), Date.from(notBefore), Date.from(notAfter),
                    subject, keyPair.getPublic());
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(domains.stream()
                    .map(d -> new GeneralName(GeneralName.dNSName, d)).toArray(GeneralName[]::new)));
            X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                    builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.getPrivate())));
            return new Issued(pem(certificate), pem(new JcaPKCS8Generator(keyPair.getPrivate(), null)), certificate);
        } catch (GeneralSecurityException | IOException | org.bouncycastle.operator.OperatorCreationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Issued selfSigned(String domain) {
        Instant now = Instant.now();
        return selfSigned(List.of(domain), now.minusSeconds(3600), now.plusSeconds(90L * 24 * 3600));
    }

    private static String pem(Object object) throws IOException {
        StringWriter out = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(out)) {
            writer.writeObject(object);
        }
        return out.toString();
    }
}
