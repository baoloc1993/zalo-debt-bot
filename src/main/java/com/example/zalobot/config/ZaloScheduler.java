package com.example.zalobot.config;

import com.example.zalobot.service.ZaloService;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class ZaloScheduler {

    private final ZaloService zaloService;

    public ZaloScheduler(ZaloService zaloService) {
        this.zaloService = zaloService;
    }

    @Scheduled(fixedRate = 60000) // Schedule to run every 60 seconds
    public void readMessages() {
        System.out.println("Scheduled task: Reading Zalo messages...");
        // TODO: Implement actual message reading logic in ZaloService
        // zaloService.readMessages();
    }
}