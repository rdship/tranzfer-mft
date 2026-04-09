package com.filetransfer.analytics.controller;

import com.filetransfer.analytics.dto.ObservatoryDto;
import com.filetransfer.analytics.service.ObservatoryService;
import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Observatory dashboard — single endpoint returning all data products.
 *
 * <pre>
 * GET /api/v1/analytics/observatory
 *   → ObservatoryData {
 *       heatmap      : 30d × 24h transfer volume cells
 *       serviceGraph : topology nodes with live health + traffic
 *       domainGroups : FlowExecution activity grouped by flow name (last 7d)
 *       generatedAt  : server timestamp
 *     }
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/analytics/observatory")
@RequiredArgsConstructor
@PreAuthorize(Roles.VIEWER)
public class ObservatoryController {

    private final ObservatoryService observatoryService;

    @GetMapping
    public ResponseEntity<ObservatoryDto.ObservatoryData> get() {
        return ResponseEntity.ok(observatoryService.getObservatoryData());
    }
}
