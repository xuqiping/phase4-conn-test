package com.superprogrammer.file.service;

/**
 * 共享访问放行钩子（记忆二期 P3 · Step 4，FR-204）。
 * <p>
 * {@link FileStorageService} 归属咽喉点的扩展分支：owner 不匹配且非 admin 时，
 * 依次咨询已注册的 grantor，任一放行即允许读（如「文件被某 ACTIVE FILE 项目记忆条目引用
 * 且请求者是该条目项目 ACTIVE 成员」）。
 * <p>
 * <b>fail-closed</b>：grantor 抛异常一律视为不放行（记日志），绝不因放行链故障开门。
 * 实现方必须自行完成全部 ACL 判定，本接口只做布尔裁决。
 */
public interface FileSharedAccessGrantor {

    /**
     * @param fileId stored_files.file_id
     * @param userId 请求者（非 owner、非 admin）
     * @return true = 放行读取；false = 维持 403
     */
    boolean canAccess(String fileId, Long userId);
}
