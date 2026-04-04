package com.filetransfer.config.controller;

import com.filetransfer.shared.connector.ActivityMonitor;
import com.filetransfer.shared.entity.ActivityEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/activity") @RequiredArgsConstructor
public class ActivityController {
    private final ActivityMonitor monitor;

    @GetMapping("/snapshot") public Map<String, Object> snapshot() { return monitor.getSnapshot(); }
    @GetMapping("/transfers") public List<ActivityEvent> activeTransfers() { return monitor.getActiveTransfers(); }
    @GetMapping("/events") public List<ActivityEvent> recentEvents(@RequestParam(defaultValue = "50") int limit) { return monitor.getRecentEvents(limit); }
}
