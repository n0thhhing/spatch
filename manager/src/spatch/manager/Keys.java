package spatch.manager;

import com.android.apksig.ApkSigner;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.TimeZone;

/**
 * This phone's signing key. It is made on first use and kept as a PKCS#12 file in the app's private
 * storage; Export and Import move it as a password-protected .p12, so a new phone (or the desktop
 * patcher) can keep updating the same install. No Android classes, so it runs in the desktop test.
 */
final class Keys {
    private static final String FILE = "signing.p12", ALIAS = "spatch";
    private static final char[] INTERNAL = "spatch-manager".toCharArray(); // the app sandbox protects the file
    private static final long YEARS_30 = 30L * 365 * 24 * 3600 * 1000;

    final PrivateKey key;
    final X509Certificate cert;

    private Keys(PrivateKey key, X509Certificate cert) {
        this.key = key;
        this.cert = cert;
    }

    static Keys loadOrCreate(File dir) throws IOException, GeneralSecurityException {
        File file = new File(dir, FILE);
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                return read(in, INTERNAL);
            }
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        Date now = new Date();
        Keys keys = new Keys(pair.getPrivate(), selfSigned(pair, "Spatch", now, new Date(now.getTime() + YEARS_30)));
        keys.save(dir);
        return keys;
    }

    /** The first key entry of a .p12, as exported here or made with keytool. */
    static Keys read(InputStream in, char[] password) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(in, password);
        for (Enumeration<String> e = store.aliases(); e.hasMoreElements();) {
            String alias = e.nextElement();
            if (!store.isKeyEntry(alias)) continue;
            Certificate cert = store.getCertificate(alias);
            if (cert instanceof X509Certificate) return new Keys((PrivateKey) store.getKey(alias, password), (X509Certificate) cert);
        }
        throw new GeneralSecurityException("That file has no signing key.");
    }

    void write(OutputStream out, char[] password) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry(ALIAS, key, password, new Certificate[] {cert});
        store.store(out, password);
    }

    void save(File dir) throws IOException, GeneralSecurityException {
        File tmp = new File(dir, FILE + ".new");
        try (OutputStream out = new FileOutputStream(tmp)) {
            write(out, INTERNAL);
        }
        if (!tmp.renameTo(new File(dir, FILE))) throw new IOException("Couldn't save the signing key.");
    }

    String fingerprint() throws GeneralSecurityException {
        return Bundle.sha256(cert.getEncoded());
    }

    void sign(File in, File out) throws Exception {
        ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder("SPATCH", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(config)).setInputApk(in).setOutputApk(out).setCreatedBy("Spatch Manager").build().sign();
    }

    /** A minimal X.509 v1 certificate (Android reads only the key and its signature). */
    static X509Certificate selfSigned(KeyPair pair, String name, Date from, Date to) throws GeneralSecurityException {
        byte[] alg = tlv(0x30, tlv(0x06, new byte[] {0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 1, 1, 0x0b}), new byte[] {5, 0});
        byte[] subject = tlv(0x30, tlv(0x31, tlv(0x30, tlv(0x06, new byte[] {0x55, 4, 3}), tlv(0x0c, name.getBytes(StandardCharsets.UTF_8)))));
        byte[] serial = tlv(0x02, new BigInteger(63, new SecureRandom()).add(BigInteger.ONE).toByteArray());
        byte[] tbs = tlv(0x30, serial, alg, subject, tlv(0x30, time(from), time(to)), subject, pair.getPublic().getEncoded());
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(pair.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign(), bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        byte[] der = tlv(0x30, tbs, alg, tlv(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }

    private static byte[] time(Date d) {
        boolean utc = d.getTime() < 2524608000000L; // UTCTime only reaches 2049
        SimpleDateFormat f = new SimpleDateFormat(utc ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(utc ? 0x17 : 0x18, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] tlv(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.write(p, 0, p.length);
        int n = body.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream(n + 6);
        out.write(tag);
        if (n < 0x80) {
            out.write(n);
        } else {
            int bytes = n > 0xffffff ? 4 : n > 0xffff ? 3 : n > 0xff ? 2 : 1;
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) out.write(n >> (8 * i));
        }
        out.write(body.toByteArray(), 0, n);
        return out.toByteArray();
    }
}
