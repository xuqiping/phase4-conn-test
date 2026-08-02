package com.superprogrammer.authorization.service;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignedEntitlementSignerTest {

    private static final long USER_ID = 42L;
    private static final String DEVICE_ID = "device-abc";

    @Test
    void signAndVerify_success() {
        KeyPair keyPair = SignedEntitlementSigner.generateKeyPair();
        Instant issuedAt = Instant.now();
        Instant notAfter = issuedAt.plusSeconds(3600);
        List<String> modules = List.of("work-report", "ai");

        String token = SignedEntitlementSigner.sign(
                keyPair.getPrivate(), USER_ID, DEVICE_ID, issuedAt, notAfter, modules);

        SignedEntitlementSigner.SignedEntitlementPayload payload =
                SignedEntitlementSigner.verify(keyPair.getPublic(), token);

        assertThat(payload.userId()).isEqualTo(USER_ID);
        assertThat(payload.deviceId()).isEqualTo(DEVICE_ID);
        assertThat(payload.issuedAtEpochMilli()).isEqualTo(issuedAt.toEpochMilli());
        assertThat(payload.notAfterEpochMilli()).isEqualTo(notAfter.toEpochMilli());
        assertThat(payload.allowedModules()).containsExactly("work-report", "ai");
    }

    @Test
    void verify_tamperedPayload_rejected() {
        KeyPair keyPair = SignedEntitlementSigner.generateKeyPair();
        Instant issuedAt = Instant.now();
        Instant notAfter = issuedAt.plusSeconds(3600);
        String token = SignedEntitlementSigner.sign(
                keyPair.getPrivate(), USER_ID, DEVICE_ID, issuedAt, notAfter, List.of("work-report"));

        String tampered = tamperPayload(token, "work-report", "clipboard");

        assertThatThrownBy(() -> SignedEntitlementSigner.verify(keyPair.getPublic(), tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature mismatch");
    }

    private String tamperPayload(String token, String oldValue, String newValue) {
        int dotIndex = token.indexOf('.');
        String payloadB64 = token.substring(0, dotIndex);
        String signatureB64 = token.substring(dotIndex + 1);
        String payload = new String(java.util.Base64.getUrlDecoder().decode(payloadB64),
                java.nio.charset.StandardCharsets.UTF_8);
        String tamperedPayload = payload.replace(oldValue, newValue);
        String tamperedPayloadB64 = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tamperedPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return tamperedPayloadB64 + "." + signatureB64;
    }

    @Test
    void verify_wrongPublicKey_rejected() {
        KeyPair signerKey = SignedEntitlementSigner.generateKeyPair();
        KeyPair otherKey = SignedEntitlementSigner.generateKeyPair();
        Instant issuedAt = Instant.now();
        Instant notAfter = issuedAt.plusSeconds(3600);
        String token = SignedEntitlementSigner.sign(
                signerKey.getPrivate(), USER_ID, DEVICE_ID, issuedAt, notAfter, List.of("work-report"));

        assertThatThrownBy(() -> SignedEntitlementSigner.verify(otherKey.getPublic(), token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature mismatch");
    }

    @Test
    void verify_malformedToken_rejected() {
        KeyPair keyPair = SignedEntitlementSigner.generateKeyPair();

        assertThatThrownBy(() -> SignedEntitlementSigner.verify(keyPair.getPublic(), "not-a-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid signed entitlement token format");
    }

    @Test
    void sign_notAfterBeforeIssuedAt_rejected() {
        KeyPair keyPair = SignedEntitlementSigner.generateKeyPair();
        Instant issuedAt = Instant.now();
        Instant notAfter = issuedAt.minusSeconds(1);

        assertThatThrownBy(() -> SignedEntitlementSigner.sign(
                keyPair.getPrivate(), USER_ID, DEVICE_ID, issuedAt, notAfter, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notAfter must not be before issuedAt");
    }

    @Test
    void pemRoundTrip_privateAndPublicKey() {
        KeyPair original = SignedEntitlementSigner.generateKeyPair();
        String privatePem = SignedEntitlementSigner.encodePrivateKeyPem(original.getPrivate());
        String publicPem = SignedEntitlementSigner.encodePublicKeyPem(original.getPublic());

        assertThat(privatePem).contains("-----BEGIN PRIVATE KEY-----");
        assertThat(publicPem).contains("-----BEGIN PUBLIC KEY-----");

        var reloadedPrivate = SignedEntitlementSigner.decodePrivateKeyPem(privatePem);
        var reloadedPublic = SignedEntitlementSigner.decodePublicKeyPem(publicPem);

        Instant issuedAt = Instant.now();
        Instant notAfter = issuedAt.plusSeconds(3600);
        String token = SignedEntitlementSigner.sign(
                reloadedPrivate, USER_ID, DEVICE_ID, issuedAt, notAfter, List.of("files"));

        SignedEntitlementSigner.SignedEntitlementPayload payload =
                SignedEntitlementSigner.verify(reloadedPublic, token);
        assertThat(payload.userId()).isEqualTo(USER_ID);
    }

    @Test
    void verify_emptyModules_success() {
        KeyPair keyPair = SignedEntitlementSigner.generateKeyPair();
        Instant issuedAt = Instant.now();
        Instant notAfter = issuedAt.plusSeconds(3600);

        String token = SignedEntitlementSigner.sign(
                keyPair.getPrivate(), USER_ID, DEVICE_ID, issuedAt, notAfter, List.of());

        SignedEntitlementSigner.SignedEntitlementPayload payload =
                SignedEntitlementSigner.verify(keyPair.getPublic(), token);

        assertThat(payload.allowedModules()).isEmpty();
    }
}
