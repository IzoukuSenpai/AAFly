package com.azurhosts.aafly.token;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signature HMAC-SHA256 des jetons de vol (SPEC §7) : {@code sig = HMAC(secret, token_id + "|" + seconds)}
 * encodée en hexadécimal minuscule.
 *
 * <p>Le secret (32 octets {@link SecureRandom}) est stocké dans {@code plugins/AAFly/token.secret},
 * créé au premier lancement (création exclusive, permissions 600 sous POSIX). Il n'est jamais loggé ni
 * exposé par {@link #toString()}. La vérification se fait en temps constant ({@link MessageDigest#isEqual}).
 * Thread-safe (un {@link Mac} est créé par appel).
 */
public final class TokenSigner {

    /** Nom du fichier secret dans le dossier du plugin. */
    public static final String SECRET_FILE_NAME = "token.secret";
    /** Taille du secret en octets. */
    public static final int SECRET_LENGTH = 32;
    /** Longueur d'une signature hexadécimale (SHA-256 = 32 octets). */
    public static final int SIGNATURE_HEX_LENGTH = 64;

    private static final String ALGORITHM = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();

    private final SecretKeySpec key;

    /**
     * @param secret secret brut de {@link #SECRET_LENGTH} octets (copié)
     * @throws IllegalArgumentException si la taille est incorrecte
     */
    public TokenSigner(byte[] secret) {
        Objects.requireNonNull(secret, "secret");
        if (secret.length != SECRET_LENGTH) {
            throw new IllegalArgumentException("Le secret doit faire " + SECRET_LENGTH + " octets.");
        }
        byte[] copy = Arrays.copyOf(secret, secret.length);
        this.key = new SecretKeySpec(copy, ALGORITHM);
        Arrays.fill(copy, (byte) 0);
        // Vérifie immédiatement que l'algorithme est disponible.
        newMac();
    }

    /**
     * Charge le secret depuis {@code file}, ou le crée s'il n'existe pas.
     *
     * @param file chemin du fichier secret
     * @return signataire prêt
     * @throws IOException si le fichier est illisible, n'est pas un fichier régulier ou a une taille invalide
     */
    public static TokenSigner loadOrCreate(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            try {
                createSecretFile(file);
            } catch (FileAlreadyExistsException race) {
                // Créé entre-temps : on le relit.
            }
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Le fichier secret des jetons n'est pas un fichier régulier : " + file.getFileName());
        }
        byte[] secret = Files.readAllBytes(file);
        try {
            if (secret.length != SECRET_LENGTH) {
                throw new IOException("Fichier secret des jetons invalide (" + file.getFileName()
                        + ") : taille attendue " + SECRET_LENGTH + " octets. Supprimez-le pour le régénérer "
                        + "(les jetons existants deviendront invalides).");
            }
            return new TokenSigner(secret);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private static void createSecretFile(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] secret = new byte[SECRET_LENGTH];
        try {
            // SecureRandom par défaut : non bloquant et cryptographiquement sûr.
            new SecureRandom().nextBytes(secret);
            boolean posix = file.getFileSystem().supportedFileAttributeViews().contains("posix");
            if (posix) {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(perms);
                Files.createFile(file, attr);
            } else {
                Files.createFile(file);
            }
            try (OutputStream out = Files.newOutputStream(file, LinkOption.NOFOLLOW_LINKS,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                out.write(secret);
            }
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    /**
     * @param tokenId identifiant du jeton
     * @param seconds montant
     * @return signature hexadécimale (64 caractères, minuscules)
     */
    public String sign(UUID tokenId, long seconds) {
        return HEX.formatHex(rawSignature(tokenId, seconds));
    }

    /**
     * Vérifie une signature en temps constant.
     *
     * @param tokenId      identifiant lu dans l'item
     * @param seconds      montant lu dans l'item
     * @param signatureHex signature lue dans l'item (peut être {@code null} ou malformée)
     * @return {@code true} si la signature est authentique
     */
    public boolean verify(UUID tokenId, long seconds, String signatureHex) {
        if (tokenId == null || signatureHex == null || signatureHex.length() != SIGNATURE_HEX_LENGTH) {
            return false;
        }
        byte[] provided;
        try {
            provided = HEX.parseHex(signatureHex);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        byte[] expected = rawSignature(tokenId, seconds);
        return MessageDigest.isEqual(expected, provided);
    }

    private byte[] rawSignature(UUID tokenId, long seconds) {
        Objects.requireNonNull(tokenId, "tokenId");
        Mac mac = newMac();
        String payload = tokenId.toString() + "|" + seconds;
        return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    }

    private Mac newMac() {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponible", e);
        }
    }

    @Override
    public String toString() {
        return "TokenSigner[HmacSHA256, secret=***]";
    }
}
