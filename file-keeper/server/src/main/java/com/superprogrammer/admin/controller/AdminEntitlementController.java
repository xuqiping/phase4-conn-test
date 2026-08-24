package com.superprogrammer.admin.controller;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.common.R;
import com.superprogrammer.user.dto.GrantEntitlementRequest;
import com.superprogrammer.user.dto.ModuleEntitlementDto;
import com.superprogrammer.user.dto.UpdateEntitlementRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users/{userId}/entitlements")
@Deprecated(forRemoval = true)
public class AdminEntitlementController {

    @GetMapping
    public R<List<ModuleEntitlementDto>> list(@PathVariable Long userId) {
        return R.ok(List.of());
    }

    @PostMapping
    public R<ModuleEntitlementDto> grant(Authentication authentication, @PathVariable Long userId,
                                          @Valid @RequestBody GrantEntitlementRequest request) {
        throw deprecatedEntitlementManagement();
    }

    @PutMapping("/{entitlementId}")
    public R<ModuleEntitlementDto> update(Authentication authentication, @PathVariable Long userId,
                                           @PathVariable Long entitlementId,
                                           @RequestBody UpdateEntitlementRequest request) {
        throw deprecatedEntitlementManagement();
    }

    @DeleteMapping("/{entitlementId}")
    public R<Void> revoke(Authentication authentication, @PathVariable Long userId,
                           @PathVariable Long entitlementId) {
        throw deprecatedEntitlementManagement();
    }

    private BusinessException deprecatedEntitlementManagement() {
        return new BusinessException(
                ErrorCode.UNPROCESSABLE,
                "模块权益管理已废弃，所有登录用户均可使用服务端模块"
        );
    }
}
