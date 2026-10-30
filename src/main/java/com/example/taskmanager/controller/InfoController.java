package com.example.taskmanager.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Exposes the deployed version so you can prove in your demo that a new
 * commit actually reached the cloud. The pipeline sets APP_VERSION to the
 * git commit SHA when it deploys.
 */
@RestController
public class InfoController {

    @Value("${app.version:local}")
    private String version;

    @GetMapping("/api/info")
    public Map<String, String> info() {
        return Map.of("app", "task-manager", "version", version);
    }
}
