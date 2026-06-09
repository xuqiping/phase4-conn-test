package com.superprogrammer.support;

import com.superprogrammer.security.RefreshTokenStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("test")
public class InMemoryRefreshTokenStore implements RefreshTokenStore {

    private final Map<String, Long> tokens = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> userTokens = new ConcurrentHashMap<>();

    @Override
    public void save(String tokenHash, Long userId, Duration ttl) {
        tokens.put(tokenHash, userId);
    }

    @Override
    public Optional<Long> findUserId(String tokenHash) {
        return Optional.ofNullable(tokens.get(tokenHash));
    }

    @Override
    public void delete(String tokenHash) {
        Long userId = tokens.remove(tokenHash);
        if (userId != null) {
            userTokens.computeIfAbsent(userId, ignored -> new HashSet<>()).remove(tokenHash);
        }
    }

    @Override
    public void addTokenToUser(Long userId, String tokenHash, Duration ttl) {
        userTokens.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(tokenHash);
    }

    @Override
    public void deleteAllForUser(Long userId) {
        Set<String> hashes = userTokens.remove(userId);
        if (hashes != null) {
            hashes.forEach(tokens::remove);
        }
    }
}
