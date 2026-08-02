-- V30: 记忆处理模式全局开关（ASYNC=全异步不卡顿 / HYBRID=同步即时冲突追问），默认 ASYNC
INSERT INTO system_settings (setting_key, setting_value, description)
VALUES ('rag.memory.process-mode', 'ASYNC',
        '记忆处理模式（ASYNC=全异步不卡顿,冲突走面板 / HYBRID=同步即时冲突追问 askText）')
ON CONFLICT (setting_key) DO NOTHING;
