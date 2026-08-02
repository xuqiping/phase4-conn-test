package com.superprogrammer.bootstrap;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SuperAdminInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    @Value("${file-keeper.bootstrap.super-admin.email:}")
    private String email;

    @Value("${file-keeper.bootstrap.super-admin.phone:}")
    private String phone;

    @Value("${file-keeper.bootstrap.super-admin.password:}")
    private String password;

    @Override
    public void run(ApplicationArguments args) {
        String normalizedEmail = normalize(email);
        String normalizedPhone = normalize(phone);

        if (!StringUtils.hasText(password) || (normalizedEmail == null && normalizedPhone == null)) {
            return;
        }

        if (superAdminExists(normalizedEmail, normalizedPhone)) {
            return;
        }

        jdbcTemplate.update(
                "insert into users (email, phone, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, 'super_admin', 'active', ?, ?, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                normalizedEmail,
                normalizedPhone,
                passwordEncoder.encode(password),
                normalizedEmail != null,
                normalizedPhone != null
        );
    }

    private boolean superAdminExists(String normalizedEmail, String normalizedPhone) {
        StringBuilder sql = new StringBuilder("select count(*) from users where role = 'super_admin' and deleted = 0 and (");
        List<Object> params = new ArrayList<>();

        if (normalizedEmail != null) {
            sql.append("email = ?");
            params.add(normalizedEmail);
        }

        if (normalizedPhone != null) {
            if (!params.isEmpty()) {
                sql.append(" or ");
            }
            sql.append("phone = ?");
            params.add(normalizedPhone);
        }

        sql.append(")");
        Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, params.toArray());
        return count != null && count > 0;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
