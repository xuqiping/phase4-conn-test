package com.superprogrammer.security;

import java.time.Duration;
import java.util.Optional;

public interface RefreshTokenStore {

    void save(String tokenHash, Long userId, Duration ttl);

    Optional<Long> findUserId(String tokenHash);

    void delete(String tokenHash);

    void addTokenToUser(Long userId, String tokenHash, Duration ttl);

    void deleteAllForUser(Long userId);
}
