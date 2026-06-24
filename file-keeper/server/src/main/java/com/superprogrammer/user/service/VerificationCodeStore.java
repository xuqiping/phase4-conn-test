package com.superprogrammer.user.service;

import java.time.Duration;

public interface VerificationCodeStore {

    void saveCode(String contactType, String contact, String code, Duration ttl);

    boolean matchesCode(String contactType, String contact, String code);

    void markVerified(String contactType, String contact, Duration ttl);

    boolean consumeVerified(String contactType, String contact);
}
