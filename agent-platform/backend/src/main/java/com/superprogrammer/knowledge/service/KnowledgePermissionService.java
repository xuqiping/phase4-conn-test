package com.superprogrammer.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.auth.entity.Department;
import com.superprogrammer.auth.entity.Role;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.DepartmentMapper;
import com.superprogrammer.auth.mapper.RoleMapper;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.dto.KnowledgePermissionRequest;
import com.superprogrammer.knowledge.dto.KnowledgePermissionVO;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.entity.KnowledgePermission;
import com.superprogrammer.knowledge.event.VisibilityInvalidationEvent;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import com.superprogrammer.knowledge.mapper.KnowledgePermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgePermissionService {

    private final KnowledgePermissionMapper permissionMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeNodeMapper nodeMapper;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final DepartmentMapper departmentMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    public List<KnowledgePermissionVO> listGrants(String targetType, Long targetId, Long operatorId, boolean admin) {
        assertManageTarget(targetType, targetId, operatorId, admin);
        LambdaQueryWrapper<KnowledgePermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgePermission::getTargetType, targetType)
                .eq(KnowledgePermission::getTargetId, targetId)
                .orderByAsc(KnowledgePermission::getSubjectType)
                .orderByAsc(KnowledgePermission::getSubjectId);
        return permissionMapper.selectList(wrapper).stream()
                .map(this::toVO)
                .toList();
    }

    @Transactional
    public KnowledgePermissionVO grant(KnowledgePermissionRequest request, Long operatorId, boolean admin) {
        assertManageTarget(request.getTargetType(), request.getTargetId(), operatorId, admin);
        String subjectName = resolveAndValidateSubject(request.getSubjectType(), request.getSubjectId());

        KnowledgePermission existing = find(request.getTargetType(), request.getTargetId(),
                request.getSubjectType(), request.getSubjectId());
        KnowledgePermission perm;
        if (existing == null) {
            perm = new KnowledgePermission();
            perm.setTenantId(1L);
            perm.setTargetType(request.getTargetType());
            perm.setTargetId(request.getTargetId());
            perm.setSubjectType(request.getSubjectType());
            perm.setSubjectId(request.getSubjectId());
        } else {
            perm = existing;
        }
        perm.setCanRead(Boolean.TRUE.equals(request.getCanRead()));
        perm.setCanWrite(Boolean.TRUE.equals(request.getCanWrite()));
        perm.setCanManage(Boolean.TRUE.equals(request.getCanManage()));
        perm.setGrantedBy(operatorId);

        if (perm.getId() == null) {
            permissionMapper.insert(perm);
        } else {
            permissionMapper.updateById(perm);
        }
        applicationEventPublisher.publishEvent(
                new VisibilityInvalidationEvent(resolveOwningKbId(request.getTargetType(), request.getTargetId())));
        return toVO(perm, subjectName);
    }

    @Transactional
    public void revoke(Long permissionId, Long operatorId, boolean admin) {
        KnowledgePermission perm = permissionMapper.selectById(permissionId);
        if (perm == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "授权记录不存在");
        }
        assertManageTarget(perm.getTargetType(), perm.getTargetId(), operatorId, admin);
        permissionMapper.deleteById(permissionId);
        applicationEventPublisher.publishEvent(
                new VisibilityInvalidationEvent(resolveOwningKbId(perm.getTargetType(), perm.getTargetId())));
    }

    /** 授权/撤销须对所属 KB 有 canManage（或 admin）。DIRECTORY/DOCUMENT 继承所属 KB。 */
    private void assertManageTarget(String targetType, Long targetId, Long operatorId, boolean admin) {
        Long kbId = resolveOwningKbId(targetType, targetId);
        if (!knowledgeBaseService.canManage(kbId, operatorId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员或知识库创建者可以管理授权");
        }
    }

    private Long resolveOwningKbId(String targetType, Long targetId) {
        switch (targetType == null ? "" : targetType) {
            case "KB":
                return targetId;
            case "DOCUMENT": {
                KnowledgeDocument doc = documentMapper.selectById(targetId);
                if (doc == null) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
                }
                return doc.getKbId();
            }
            case "DIRECTORY": {
                KnowledgeNode node = nodeMapper.selectById(targetId);
                if (node == null) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "目录不存在");
                }
                return node.getKbId();
            }
            default:
                throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的授权目标类型: " + targetType);
        }
    }

    private String resolveAndValidateSubject(String subjectType, Long subjectId) {
        switch (subjectType == null ? "" : subjectType) {
            case "USER": {
                User user = userMapper.selectById(subjectId);
                if (user == null) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "被授权用户不存在");
                }
                return user.getUsername();
            }
            case "ROLE": {
                Role role = roleMapper.selectById(subjectId);
                if (role == null) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "被授权角色不存在");
                }
                return role.getName();
            }
            case "DEPARTMENT": {
                Department dept = departmentMapper.selectById(subjectId);
                if (dept == null) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "被授权部门不存在");
                }
                return dept.getName();
            }
            default:
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "Phase1 仅支持 USER / ROLE / DEPARTMENT 授权主体: " + subjectType);
        }
    }

    private KnowledgePermission find(String targetType, Long targetId, String subjectType, Long subjectId) {
        LambdaQueryWrapper<KnowledgePermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgePermission::getTargetType, targetType)
                .eq(KnowledgePermission::getTargetId, targetId)
                .eq(KnowledgePermission::getSubjectType, subjectType)
                .eq(KnowledgePermission::getSubjectId, subjectId);
        return permissionMapper.selectOne(wrapper);
    }

    private KnowledgePermissionVO toVO(KnowledgePermission perm) {
        return toVO(perm, resolveSubjectName(perm.getSubjectType(), perm.getSubjectId()));
    }

    private KnowledgePermissionVO toVO(KnowledgePermission perm, String subjectName) {
        return KnowledgePermissionVO.builder()
                .id(perm.getId())
                .targetType(perm.getTargetType())
                .targetId(perm.getTargetId())
                .subjectType(perm.getSubjectType())
                .subjectId(perm.getSubjectId())
                .subjectName(subjectName)
                .canRead(perm.getCanRead())
                .canWrite(perm.getCanWrite())
                .canManage(perm.getCanManage())
                .grantedBy(perm.getGrantedBy())
                .createdAt(perm.getCreatedAt())
                .build();
    }

    private String resolveSubjectName(String subjectType, Long subjectId) {
        if (subjectId == null) {
            return null;
        }
        try {
            return resolveAndValidateSubject(subjectType, subjectId);
        } catch (BusinessException ignored) {
            return null;
        }
    }
}
