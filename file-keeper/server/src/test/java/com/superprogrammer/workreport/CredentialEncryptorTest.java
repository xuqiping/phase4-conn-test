package com.superprogrammer.workreport;

import com.superprogrammer.workreport.service.CredentialEncryptor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class CredentialEncryptorTest {

    @Test
    void returnsPlainTextWhenKeyNotConfigured() {
        CredentialEncryptor encryptor = new CredentialEncryptor();
        ReflectionTestUtils.setField(encryptor, "key", "");
        encryptor.init();

        assertEquals("plain", encryptor.encrypt("plain"));
        assertEquals("plain", encryptor.decrypt("plain"));
    }

    @Test
    void encryptAndDecrypt() {
        CredentialEncryptor encryptor = new CredentialEncryptor();
        ReflectionTestUtils.setField(encryptor, "key", "my-secret-key-for-testing-only!!");
        encryptor.init();

        String plain = "{\"appId\":\"123\",\"appSecret\":\"secret\"}";
        String cipher = encryptor.encrypt(plain);

        assertNotEquals(plain, cipher);
        assertEquals(plain, encryptor.decrypt(cipher));
    }

    @Test
    void handlesNullValues() {
        CredentialEncryptor encryptor = new CredentialEncryptor();
        ReflectionTestUtils.setField(encryptor, "key", "test-key-32-chars-long!!!!!!");
        encryptor.init();

        assertNull(encryptor.encrypt(null));
        assertNull(encryptor.decrypt(null));
    }
}
