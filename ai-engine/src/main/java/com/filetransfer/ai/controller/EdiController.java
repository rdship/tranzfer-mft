package com.filetransfer.ai.controller;

import com.filetransfer.ai.service.edi.EdiTranslationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/v1/edi") @RequiredArgsConstructor
public class EdiController {

    private final EdiTranslationService ediService;

    @PostMapping("/detect")
    public ResponseEntity<?> detect(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ediService.detect(body.get("content")));
    }

    @PostMapping("/validate")
    public ResponseEntity<?> validate(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ediService.validate(body.get("content")));
    }

    @PostMapping("/translate/json")
    public ResponseEntity<?> toJson(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ediService.translateToJson(body.get("content")));
    }

    @PostMapping("/translate/csv")
    public ResponseEntity<String> toCsv(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok().header("Content-Type", "text/csv").body(ediService.translateToCsv(body.get("content")));
    }

    @GetMapping("/types")
    public Map<String, String> supportedTypes() {
        return Map.of("837","Health Care Claim","835","Health Care Payment",
                "850","Purchase Order","856","Ship Notice","270","Eligibility Inquiry",
                "271","Eligibility Response","997","Functional Ack","810","Invoice");
    }
}
