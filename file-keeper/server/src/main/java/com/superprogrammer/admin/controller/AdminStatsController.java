package com.superprogrammer.admin.controller;

import com.superprogrammer.admin.service.AdminStatsService;
import com.superprogrammer.common.R;
import com.superprogrammer.stats.dto.DashboardStats;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/stats")
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminStatsService adminStatsService;

    @GetMapping("/dashboard")
    public R<DashboardStats> dashboard() {
        return R.ok(adminStatsService.dashboard());
    }
}
