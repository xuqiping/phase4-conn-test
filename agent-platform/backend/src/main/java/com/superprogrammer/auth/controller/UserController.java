// agent-platform/backend/src/main/java/com/superprogrammer/auth/controller/UserController.java
package com.superprogrammer.auth.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.auth.dto.UserVO;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.auth.service.AuthService;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserMapper userMapper;
    private final AuthService authService;

    @GetMapping
    @PreAuthorize("hasAuthority('user:manage')")
    public ResponseEntity<R<PageResult<UserVO>>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<User> userPage = userMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<User>().orderByDesc(User::getCreatedAt)
        );

        var vos = userPage.getRecords().stream().map(user ->
                authService.getCurrentUser(user.getId())
        ).toList();

        PageResult<UserVO> result = PageResult.of(
                vos, userPage.getTotal(), page, size);
        return ResponseEntity.ok(R.ok(result));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user:manage')")
    public ResponseEntity<R<UserVO>> getUser(@PathVariable Long id) {
        UserVO userVO = authService.getCurrentUser(id);
        return ResponseEntity.ok(R.ok(userVO));
    }
}
