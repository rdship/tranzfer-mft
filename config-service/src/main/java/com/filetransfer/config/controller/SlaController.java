package com.filetransfer.config.controller;

import com.filetransfer.shared.entity.PartnerAgreement;
import com.filetransfer.shared.repository.PartnerAgreementRepository;
import com.filetransfer.shared.scheduler.SlaBreachDetector;
import com.filetransfer.shared.security.Roles;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/sla") @RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
public class SlaController {
    private final PartnerAgreementRepository repo;
    private final SlaBreachDetector breachDetector;

    @GetMapping public List<PartnerAgreement> list() { return repo.findByActiveTrue(); }
    @PostMapping public ResponseEntity<PartnerAgreement> create(@RequestBody PartnerAgreement sla) { sla.setId(null); return ResponseEntity.status(HttpStatus.CREATED).body(repo.save(sla)); }
    @PutMapping("/{id}") public PartnerAgreement update(@PathVariable UUID id, @RequestBody PartnerAgreement sla) { if (!repo.existsById(id)) throw new EntityNotFoundException("Not found"); sla.setId(id); return repo.save(sla); }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id) { repo.deleteById(id); return ResponseEntity.noContent().build(); }
    @GetMapping("/breaches") public List<SlaBreachDetector.SlaBreachEvent> breaches() { return breachDetector.getActiveBreaches(); }
}
