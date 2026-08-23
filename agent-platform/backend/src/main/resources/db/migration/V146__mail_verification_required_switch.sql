-- 12x 开关回退：邮箱验证总开关 seed（system_settings，幂等）。
-- 默认 false=关：注册不强制邮箱验证码、充值下单不强制已验证邮箱（真实邮箱通道接入前的人工测试态）。
-- 接入腾讯 SMTP 并「测试发信」通过后，在 设置→认证通道→邮件通道 把本开关翻开，
-- B1（注册强制 6 位邮箱码）+ B5（充值需已验证邮箱）即整体复活。
INSERT INTO system_settings (setting_key, setting_value, description) VALUES
    ('auth.channel.mail.verification-required', 'false',
     '邮箱验证总开关（12x）：开=注册强制邮箱验证码+充值需已验证邮箱；关=邮箱可选填不验码')
ON CONFLICT (setting_key) DO NOTHING;
