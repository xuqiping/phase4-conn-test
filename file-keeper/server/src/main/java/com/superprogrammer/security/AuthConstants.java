package com.superprogrammer.security;

public final class AuthConstants {

    public static final String ROLE_SUPER_ADMIN = "super_admin";
    public static final String ROLE_USER = "user";
    public static final String STATUS_PENDING_VERIFICATION = "pending_verification";
    public static final String STATUS_PENDING_REVIEW = "pending_review";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";

    public static final String MODULE_FILES = "files";
    public static final String MODULE_PROCESSES = "processes";
    public static final String MODULE_CLIPBOARD = "clipboard";
    public static final String MODULE_WORK_REPORT = "work-report";
    public static final String MODULE_AI = "ai";

    public static final int ANONYMOUS_FULL_TRIAL_DAYS = 7;
    public static final int ANONYMOUS_FREE_MODULE_CHANGE_DAYS = 30;

    private AuthConstants() {
    }
}
