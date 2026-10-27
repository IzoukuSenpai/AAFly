package com.azurhosts.aafly.token;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TokenSignerTest {

    private static byte[] secret(int fill) {
        byte[] s = new byte[TokenSigner.SECRET_LENGTH];
        Arrays.fill(s, (byte) fill);
        return s;
    }

    @Test
    void signatureDeterministeEtVerifiable() {
        TokenSigner signer = new TokenSigner(secret(7));
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        String sig = signer.sign(id, 3_600L);
        assertEquals(TokenSigner.SIGNATURE_HEX_LENGTH, sig.length());
        assertEquals(sig, signer.sign(id, 3_600L));
        assertTrue(signer.verify(id, 3_600L, sig));
    }

    @Test
    void falsificationsRejetees() {
        TokenSigner signer = new TokenSigner(secret(7));
        UUID id = UUID.randomUUID();
        String sig = signer.sign(id, 60L);
        assertFalse(signer.verify(id, 61L, sig), "montant modifié");
        assertFalse(signer.verify(UUID.randomUUID(), 60L, sig), "identifiant modifié");
        assertFalse(signer.verify(id, 60L, null));
        assertFalse(signer.verify(id, 60L, ""));
        assertFalse(signer.verify(id, 60L, "zz".repeat(32)), "hex invalide");
        assertFalse(signer.verify(id, 60L, sig.substring(2)), "longueur invalide");
        assertFalse(new TokenSigner(secret(8)).verify(id, 60L, sig), "autre secret");
        // La casse de l'hexadécimal est tolérée (décodage), la valeur reste authentique.
        assertTrue(signer.verify(id, 60L, sig.toUpperCase(java.util.Locale.ROOT)));
    }

    @Test
    void vecteurHmacConnu() {
        // HMAC-SHA256(clé = 32 octets 0x0b, "00000000-0000-0000-0000-000000000000|1"), calculé indépendamment.
        TokenSigner signer = new TokenSigner(secret(0x0b));
        String sig = signer.sign(new UUID(0L, 0L), 1L);
        assertEquals("430f8048bef31154c3d12406ded5d2e9e5f40bb9d8eaa2e09297165797345327", sig);
        assertNotEquals(sig, signer.sign(new UUID(0L, 0L), 2L));
    }

    @Test
    void secretDeMauvaiseTaille() {
        assertThrows(IllegalArgumentException.class, () -> new TokenSigner(new byte[16]));
    }

    @Test
    void creationEtRelectureDuSecret(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("sub").resolve(TokenSigner.SECRET_FILE_NAME);
        TokenSigner first = TokenSigner.loadOrCreate(file);
        assertTrue(Files.isRegularFile(file));
        assertEquals(TokenSigner.SECRET_LENGTH, Files.size(file));
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
        TokenSigner second = TokenSigner.loadOrCreate(file);
        UUID id = UUID.randomUUID();
        assertTrue(second.verify(id, 42L, first.sign(id, 42L)), "le secret doit être réutilisé");
        assertFalse(first.toString().contains(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1)));
    }

    @Test
    void secretCorrompu(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(TokenSigner.SECRET_FILE_NAME);
        Files.write(file, new byte[5]);
        assertThrows(IOException.class, () -> TokenSigner.loadOrCreate(file));
    }
}
