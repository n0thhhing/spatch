package spatch;

import android.util.Log;
import java.nio.charset.StandardCharsets;

/**
 * The audio decryptor: every streamed sample goes through NativeCryptoProvider.chachaDecrypt as
 * ChaCha20-Poly1305 with a per-track key and nonce. This both records that key (so a track can be
 * decrypted outside the core) and does the decryption itself, using the same cipher.
 */
public final class AudioCrypto {
    private static final String TAG = "SpatchDecrypt";

    private AudioCrypto() {
    }

    private static volatile String last;

    /** Hook: the key and nonce the core is about to decrypt one sample with. Kept for the experiment. */
    public static void seen(byte[] key, byte[] nonce) {
        if (key == null || nonce == null) return;
        // Only 32-byte keys are audio keys; shorter/longer pairs are other protocols.
        if (key.length != 32) return;
        String pair = hex(key) + " " + hex(nonce);
        if (pair.equals(last)) return;
        last = pair;
        Log.i(TAG, "key=" + pair);
    }

    /** The last pair as "keyhext noncehex", or null before the first decrypt. */
    public static String lastKey() {
        return last;
    }

    /** Hook: the same decrypt the core just did, so a captured key can be checked against its own bytes. */
    public static byte[] decryptWith(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad) throws Exception {
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "ChaCha20"),
            new javax.crypto.spec.IvParameterSpec(nonce));
        if (aad != null && aad.length > 0) cipher.updateAAD(aad);
        return cipher.doFinal(ciphertext);
    }

    /** Decrypts a whole downloaded file: returns the plaintext, or the error text if the key is wrong. */
    public static String decryptFile(byte[] key, byte[] nonce, byte[] aad, byte[] body) {
        try {
            byte[] plain = decryptWith(key, nonce, body, aad);
            return "ok " + plain.length + " bytes head=" + new String(plain, 0, Math.min(8, plain.length), StandardCharsets.ISO_8859_1).replaceAll("[^\\x20-\\x7e]", ".");
        } catch (Exception failed) {
            return "failed: " + failed;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b));
        return out.toString();
    }
}
