package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.chat.mapper.MemoryRecallAclMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 计划12 · 迭代 I1：项目记忆读取 ACL 前置 resolver。
 * <p>
 * {@code readableAuthors(projectId, reader)} = 项目内某 reader 可读哪些【作者】的流水账
 * （召回 + 总结取数共用同一套，总体设计 §3.6 + §6 向量 14）。五路径：
 * <ol>
 *   <li><b>owner</b> → 项目全部成员 user_id（含 DEPARTED 曾赋权，保交接）；owner 无需 ACL 行兜底全读。</li>
 *   <li><b>admin（recall_admin=false）</b> → ACL 授权集 ∪ {自己}。</li>
 *   <li><b>member</b> → ACL 授权集 ∪ {自己}。</li>
 *   <li><b>recall_admin=true 的 admin</b> → 仍 ACL 集 ∪ {自己}（契约：recall_admin 仅多「配 ACL」权，
 *       读不扩——与普通 admin/member 一致；配权边界在 I2 端点判）。</li>
 *   <li><b>非成员</b> → 空集（无项目读权；自己流水账经个人 scope 召回，不经项目 ACL）。</li>
 * </ol>
 * <p>
 * <b>DEPARTED 保留</b>：曾授权的 target（ACL 行）/ 曾在册的成员（owner 全员路径）状态离职后仍留在结果集
 * （保交接）；是否真正纳入召回由 L10「同步已离开人员」开关在 I3 接入时过滤+标注，本 resolver 不滤。
 * <p>
 * <b>summary 不受 ACL 影响</b>（恒只读自己，向量 14）——本 resolver 只管流水账读取范围。
 *
 * @see MemoryRecallAclMapper ACL 数据出口
 * @see MemoryProjectMemberMapper 成员角色/在册判定
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRecallAclResolver {

    private static final String ROLE_OWNER = "OWNER";

    private final MemoryProjectMemberMapper memberMapper;
    private final MemoryRecallAclMapper recallAclMapper;

    /**
     * 项目内 reader 可读的作者 user_id 集。
     *
     * @param projectId     项目 id（null → 空集，个人 scope 不走本 resolver）
     * @param readerUserId  读者 user id（null → 空集）
     * @return 不可变语义的可读作者集（含 DEPARTED，由调用方按 L10 再过滤）；空集 = 无权
     */
    public Set<Long> readableAuthors(Long projectId, Long readerUserId) {
        if (projectId == null || readerUserId == null) {
            return Collections.emptySet();
        }

        // 读者在项目内的成员行（角色 + recall_admin + status）——成员身份是项目读权的前提
        MemoryProjectMember reader = memberMapper.selectOne(
                new LambdaQueryWrapper<MemoryProjectMember>()
                        .eq(MemoryProjectMember::getProjectId, projectId)
                        .eq(MemoryProjectMember::getUserId, readerUserId));
        if (reader == null) {
            log.debug("readableAuthors 非成员 projectId={} reader={} → 空（个人 scope 不经项目 ACL）",
                    projectId, readerUserId);
            return Collections.emptySet();
        }

        // owner 兜底全读：项目全部成员（ACTIVE + DEPARTED，保交接），无需 ACL 行
        if (ROLE_OWNER.equals(reader.getRole())) {
            List<MemoryProjectMember> all = memberMapper.selectList(
                    new LambdaQueryWrapper<MemoryProjectMember>()
                            .eq(MemoryProjectMember::getProjectId, projectId));
            Set<Long> authors = all.stream()
                    .map(MemoryProjectMember::getUserId)
                    .collect(Collectors.toCollection(HashSet::new));
            log.debug("readableAuthors owner projectId={} reader={} → 全部 {} 名成员（含 DEPARTED，L10 在 I3 过滤）",
                    projectId, readerUserId, authors.size());
            return authors;
        }

        // admin / member（含 recall_admin=true）：ACL 授权集 ∪ {自己}
        // recall_admin 仅配权（I2 端点判），读不扩——契约与普通 admin/member 一致
        List<Long> granted = recallAclMapper.findGrantedTargetIds(projectId, readerUserId);
        Set<Long> authors = new HashSet<>(granted);
        authors.add(readerUserId);  // 自己总能读自己挂该项目的流水账
        log.debug("readableAuthors {} projectId={} reader={} recallAdmin={} → ACL {} 人 + 自己 = {} 人（含 DEPARTED 曾赋权）",
                reader.getRole(), projectId, readerUserId, reader.getRecallAdmin(),
                granted.size(), authors.size());
        return authors;
    }
}
