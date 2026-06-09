package com.superprogrammer.security;

public record AuthPrincipal(Long userId, String role, String status) {
}
