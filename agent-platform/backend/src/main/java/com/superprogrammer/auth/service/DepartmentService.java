package com.superprogrammer.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.auth.dingtalk.service.DingTalkService.DingTalkDept;
import com.superprogrammer.auth.dto.DepartmentRequest;
import com.superprogrammer.auth.dto.DepartmentVO;
import com.superprogrammer.auth.entity.Department;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.entity.UserDepartment;
import com.superprogrammer.auth.mapper.DepartmentMapper;
import com.superprogrammer.auth.mapper.UserDepartmentMapper;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DepartmentService {

    private static final Long TENANT_ID = 1L;

    private final DepartmentMapper departmentMapper;
    private final UserDepartmentMapper userDepartmentMapper;
    private final UserMapper userMapper;

    public List<DepartmentVO> list() {
        LambdaQueryWrapper<Department> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Department::getTenantId, TENANT_ID)
                .orderByAsc(Department::getSortOrder)
                .orderByAsc(Department::getId);
        return departmentMapper.selectList(wrapper).stream()
                .map(this::toVO)
                .toList();
    }

    @Transactional
    public DepartmentVO create(DepartmentRequest request, Long operatorId) {
        Department dept = new Department();
        dept.setTenantId(TENANT_ID);
        applyRequest(dept, request);
        dept.setStatus("ACTIVE");
        dept.setCreatedBy(operatorId);
        departmentMapper.insert(dept);
        return toVO(dept);
    }

    @Transactional
    public DepartmentVO update(Long id, DepartmentRequest request) {
        Department dept = ensure(id);
        applyRequest(dept, request);
        departmentMapper.updateById(dept);
        return toVO(dept);
    }

    @Transactional
    public void delete(Long id) {
        ensure(id);
        departmentMapper.deleteById(id);
        // 成员关联随 ON DELETE CASCADE 由 DB 清理（user_departments.department_id FK）
    }

    @Transactional
    public void assignMember(Long userId, Long departmentId, boolean isPrimary, Long operatorId) {
        ensure(departmentId);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        LambdaQueryWrapper<UserDepartment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserDepartment::getUserId, userId)
                .eq(UserDepartment::getDepartmentId, departmentId);
        UserDepartment rel = userDepartmentMapper.selectOne(wrapper);
        if (rel == null) {
            rel = new UserDepartment();
            rel.setUserId(userId);
            rel.setDepartmentId(departmentId);
            rel.setIsPrimary(isPrimary);
            rel.setCreatedBy(operatorId);
            userDepartmentMapper.insert(rel);
        } else {
            rel.setIsPrimary(isPrimary);
            userDepartmentMapper.updateById(rel);
        }
    }

    @Transactional
    public void removeMember(Long userId, Long departmentId) {
        LambdaQueryWrapper<UserDepartment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserDepartment::getUserId, userId)
                .eq(UserDepartment::getDepartmentId, departmentId);
        userDepartmentMapper.delete(wrapper);
    }

    /**
     * 钉钉部门同步：按 dingtalkDeptId 建/匹配本地部门，并关联到用户。
     * <p>主部门规则：取首个 deptId ≠ 1（钉钉根部门）的部门；全为根则取首个。
     * assignMember 是 (user_id,dept_id) upsert，重复同步不累积。
     */
    @Transactional
    public void syncUserDepartmentsFromDingtalk(Long userId, List<DingTalkDept> depts, Long operatorId) {
        if (depts == null || depts.isEmpty()) {
            return;
        }
        boolean primaryAssigned = false;
        for (DingTalkDept d : depts) {
            Department local = findOrCreateByDingtalkDept(d.deptId(), d.name(), operatorId);
            // 主部门：首个非根部门
            boolean isPrimary = !primaryAssigned && d.deptId() != 1L;
            if (isPrimary) {
                primaryAssigned = true;
            }
            assignMember(userId, local.getId(), isPrimary, operatorId);
        }
        // 全为根部门的情况：首个设主
        if (!primaryAssigned) {
            Department local = findOrCreateByDingtalkDept(depts.get(0).deptId(), depts.get(0).name(), operatorId);
            assignMember(userId, local.getId(), true, operatorId);
        }
        log.info("钉钉部门同步完成: userId={}, depts={}", userId, depts);
    }

    /** 按 dingtalkDeptId 查本地部门；不存在则按钉钉部门名建（code = dt_{deptId} 保证唯一） */
    private Department findOrCreateByDingtalkDept(long dingtalkDeptId, String name, Long operatorId) {
        LambdaQueryWrapper<Department> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Department::getTenantId, TENANT_ID)
                .eq(Department::getDingtalkDeptId, dingtalkDeptId);
        Department dept = departmentMapper.selectOne(wrapper);
        if (dept != null) {
            // 名称可能变更，原地刷新
            if (name != null && !name.equals(dept.getName())) {
                dept.setName(name);
                departmentMapper.updateById(dept);
            }
            return dept;
        }
        dept = new Department();
        dept.setTenantId(TENANT_ID);
        dept.setName(name);
        dept.setCode("dt_" + dingtalkDeptId);
        dept.setDingtalkDeptId(dingtalkDeptId);
        dept.setSortOrder(0);
        dept.setStatus("ACTIVE");
        dept.setCreatedBy(operatorId);
        departmentMapper.insert(dept);
        return dept;
    }

    /** 取用户主部门名（is_primary=true），无则 null */
    public String getPrimaryDepartmentName(Long userId) {
        LambdaQueryWrapper<UserDepartment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserDepartment::getUserId, userId)
                .eq(UserDepartment::getIsPrimary, true);
        UserDepartment rel = userDepartmentMapper.selectOne(wrapper);
        if (rel == null) {
            return null;
        }
        Department dept = departmentMapper.selectById(rel.getDepartmentId());
        return dept == null ? null : dept.getName();
    }

    private void applyRequest(Department dept, DepartmentRequest request) {
        dept.setName(request.getName());
        dept.setCode(request.getCode());
        dept.setParentId(request.getParentId());
        dept.setDescription(request.getDescription());
        dept.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
    }

    private Department ensure(Long id) {
        Department dept = departmentMapper.selectById(id);
        if (dept == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部门不存在");
        }
        return dept;
    }

    private DepartmentVO toVO(Department dept) {
        return DepartmentVO.builder()
                .id(dept.getId())
                .tenantId(dept.getTenantId())
                .name(dept.getName())
                .code(dept.getCode())
                .parentId(dept.getParentId())
                .description(dept.getDescription())
                .sortOrder(dept.getSortOrder())
                .status(dept.getStatus())
                .createdAt(dept.getCreatedAt())
                .build();
    }
}
