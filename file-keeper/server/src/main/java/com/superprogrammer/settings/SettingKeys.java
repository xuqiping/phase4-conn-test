package com.superprogrammer.settings;

/**
 * 全局系统配置项的 key 字符串与兜底默认值。
 * 运行时实际值从 system_settings 表读取，表空时使用这里的默认值。
 */
public final class SettingKeys {

    public static final String DEFAULT_DEVICE_LIMIT = "default_device_limit";
    public static final String DEFAULT_OFFLINE_CACHE_MINUTES = "default_offline_cache_minutes";
    public static final String ANONYMOUS_TRIAL_DAYS = "anonymous_trial_days";
    public static final String FREE_MODULE_CHANGE_DAYS = "free_module_change_days";

    public static final int DEFAULT_DEVICE_LIMIT_VALUE = 1;
    public static final int DEFAULT_OFFLINE_CACHE_MINUTES_VALUE = 0;
    public static final int DEFAULT_ANONYMOUS_TRIAL_DAYS_VALUE = 7;
    public static final int DEFAULT_FREE_MODULE_CHANGE_DAYS_VALUE = 30;

    public static final String DESCRIPTION_DEFAULT_DEVICE_LIMIT = "新用户默认设备上限";
    public static final String DESCRIPTION_DEFAULT_OFFLINE_CACHE_MINUTES = "新用户默认离线缓存时长（分钟）";
    public static final String DESCRIPTION_ANONYMOUS_TRIAL_DAYS = "匿名设备全功能试用天数";
    public static final String DESCRIPTION_FREE_MODULE_CHANGE_DAYS = "匿名免费模块更换间隔（天）";

    private SettingKeys() {
    }
}
