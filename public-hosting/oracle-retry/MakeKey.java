import java.nio.file.*;
import java.security.*;
import java.util.Base64;

/**
 * Makes the RSA key pair Oracle's API uses to recognise this PC, and prints the key's fingerprint.
 * The private key stays in the folder given as the first argument; only the public key goes to Oracle.
 *
 *   java MakeKey.java C:\Users\you\.oci
 */
public class MakeKey {
    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        Files.createDirectories(dir);
        Path priv = dir.resolve("ssm_api_key.pem"), pub = dir.resolve("ssm_api_key_public.pem");
        if (Files.exists(priv)) throw new IllegalStateException(priv + " already exists; not replacing it");
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        Files.writeString(priv, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        Files.writeString(pub, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        StringBuilder print = new StringBuilder();
        for (byte b : MessageDigest.getInstance("MD5").digest(pair.getPublic().getEncoded()))
            print.append(print.length() == 0 ? "" : ":").append(String.format("%02x", b));
        System.out.println("fingerprint=" + print);
        System.out.println("public key: " + pub);
    }

    static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
            + "\n-----END " + label + "-----\n";
    }
}
