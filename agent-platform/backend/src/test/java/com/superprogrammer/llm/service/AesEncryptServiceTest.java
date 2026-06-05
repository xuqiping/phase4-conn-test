package com.superprogrammer.llm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AesEncryptServiceTest {

    private AesEncryptService service;

    @BeforeEach
    void setUp() {
        service = new AesEncryptService();
        service.setSecret("test-secret-key-32-bytes-long!!!");
    }

    @Test
    void encryptAndDecrypt_shouldReturnOriginalText() {
        String original = "sk-my-api-key-12345";
        String encrypted = service.encrypt(original);
        assertNotEquals(original, encrypted);
        String decrypted = service.decrypt(encrypted);
        assertEquals(original, decrypted);
    }

    @Test
    void encrypt_shouldReturnDifferentCiphertextEachTime() {
        String original = "same-text";
        String enc1 = service.encrypt(original);
        String enc2 = service.encrypt(original);
        assertNotEquals(enc1, enc2);
    }

    @Test
    void decrypt_withInvalidInput_shouldThrow() {
        assertThrows(Exception.class, () -> service.decrypt("not-valid-base64"));
    }
}
