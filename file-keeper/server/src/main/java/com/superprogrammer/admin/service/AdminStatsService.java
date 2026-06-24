package com.superprogrammer.admin.service;

import com.superprogrammer.stats.dto.DashboardStats;
import com.superprogrammer.stats.repository.StatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminStatsService {

    private final StatsRepository statsRepository;

    public DashboardStats dashboard() {
        return statsRepository.loadDashboard();
    }
}
