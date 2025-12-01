package com.example.zalobot.config;

import com.example.zalobot.service.ZaloService;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
@EnableScheduling
public class ZaloScheduler {

    private final ZaloService zaloService;

    @Value("${zalo.scheduled.users:}")
    private String scheduledUsersString;

    public ZaloScheduler(ZaloService zaloService) {
        this.zaloService = zaloService;
    }

    @Scheduled(fixedRate = 60000) // Schedule to run every 60 seconds
    public void readMessages() {
        System.out.println("Scheduled task: Reading Zalo messages...");
        zaloService.refreshAccessToken().block(); // Ensure token is refreshed before reading messages
        List<String> targetUserIds = null;
        if (scheduledUsersString != null && !scheduledUsersString.isEmpty()) {
            targetUserIds = Arrays.stream(scheduledUsersString.split(","))
                                  .map(String::trim)
                                  .filter(s -> !s.isEmpty())
                                  .collect(Collectors.toList());
        }
        zaloService.readMessages(targetUserIds).subscribe();
    }
}