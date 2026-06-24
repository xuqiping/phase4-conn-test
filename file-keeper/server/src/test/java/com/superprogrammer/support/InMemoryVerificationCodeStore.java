package com.superprogrammer.support;

import com.superprogrammer.user.service.VerificationCodeStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("test")
public class InMemoryVerificationCodeStore implements VerificationCodeStore {

    private final Map<String, String> codes = new ConcurrentHashMap<>();
    private final Map<String, Boolean> verified = new ConcurrentHashMap<>();

    @Override
    public void saveCode(String contactType, String contact, String code, Duration ttl) {
        codes.put(key(contactType, contact), code);
    }

    @Override
    public boolean matchesCode(String contactType, String contact, String code) {
        return code.equals(codes.get(key(contactType, contact)));
    }

    @Override
    public void markVerified(String contactType, String contact, Duration ttl) {
        verified.put(key(contactType, contact), true);
    }

    @Override
    public boolean consumeVerified(String contactType, String contact) {
        boolean exists = Boolean.TRUE.equals(verified.remove(key(contactType, contact)));
        if (exists) {
            codes.remove(key(contactType, contact));
        }
        return exists;
    }

    private String key(String contactType, String contact) {
        return contactType + ":" + contact;
    }
}
