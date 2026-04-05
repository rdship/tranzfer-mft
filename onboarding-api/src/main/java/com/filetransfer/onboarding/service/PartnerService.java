package com.filetransfer.onboarding.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.onboarding.dto.request.CreatePartnerRequest;
import com.filetransfer.onboarding.dto.request.UpdatePartnerRequest;
import com.filetransfer.onboarding.dto.response.PartnerDetailResponse;
import com.filetransfer.shared.entity.DeliveryEndpoint;
import com.filetransfer.shared.entity.FileFlow;
import com.filetransfer.shared.entity.Partner;
import com.filetransfer.shared.entity.PartnerContact;
import com.filetransfer.shared.entity.TransferAccount;
import com.filetransfer.shared.repository.DeliveryEndpointRepository;
import com.filetransfer.shared.repository.FileFlowRepository;
import com.filetransfer.shared.repository.PartnerContactRepository;
import com.filetransfer.shared.repository.PartnerRepository;
import com.filetransfer.shared.repository.TransferAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PartnerService {

    private final PartnerRepository partnerRepository;
    private final PartnerContactRepository contactRepository;
    private final TransferAccountRepository accountRepository;
    private final FileFlowRepository fileFlowRepository;
    private final DeliveryEndpointRepository deliveryEndpointRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public Partner createPartner(CreatePartnerRequest request, String createdBy) {
        String slug = generateSlug(request.getCompanyName());

        if (partnerRepository.existsBySlug(slug)) {
            throw new IllegalArgumentException("Partner slug already exists: " + slug);
        }

        Partner partner = Partner.builder()
                .companyName(request.getCompanyName())
                .displayName(request.getDisplayName())
                .slug(slug)
                .industry(request.getIndustry())
                .website(request.getWebsite())
                .logoUrl(request.getLogoUrl())
                .partnerType(request.getPartnerType() != null ? request.getPartnerType() : "EXTERNAL")
                .status("PENDING")
                .onboardingPhase("SETUP")
                .protocolsEnabled(toJson(request.getProtocolsEnabled()))
                .slaTier(request.getSlaTier() != null ? request.getSlaTier() : "STANDARD")
                .maxFileSizeBytes(request.getMaxFileSizeBytes() != null ? request.getMaxFileSizeBytes() : 536870912L)
                .maxTransfersPerDay(request.getMaxTransfersPerDay() != null ? request.getMaxTransfersPerDay() : 1000)
                .retentionDays(request.getRetentionDays() != null ? request.getRetentionDays() : 90)
                .notes(request.getNotes())
                .build();
        partner.setCreatedBy(createdBy);

        partnerRepository.save(partner);
        log.info("Created partner id={} slug={} createdBy={}", partner.getId(), partner.getSlug(), createdBy);

        if (request.getContacts() != null) {
            for (CreatePartnerRequest.ContactRequest cr : request.getContacts()) {
                PartnerContact contact = PartnerContact.builder()
                        .partner(partner)
                        .name(cr.getName())
                        .email(cr.getEmail())
                        .phone(cr.getPhone())
                        .role(cr.getRole() != null ? cr.getRole() : "Technical")
                        .isPrimary(cr.isPrimary())
                        .build();
                contactRepository.save(contact);
            }
        }

        return partner;
    }

    public PartnerDetailResponse getPartner(UUID id) {
        Partner partner = findById(id);
        List<PartnerContact> contacts = contactRepository.findByPartnerId(id);
        List<TransferAccount> accounts = accountRepository.findByPartnerId(id);
        long accountCount = accountRepository.countByPartnerId(id);
        long flowCount = fileFlowRepository.countByPartnerId(id);
        long endpointCount = deliveryEndpointRepository.countByPartnerId(id);

        return PartnerDetailResponse.builder()
                .partner(partner)
                .contacts(contacts)
                .accountCount(accountCount)
                .flowCount(flowCount)
                .endpointCount(endpointCount)
                .accounts(accounts)
                .build();
    }

    public List<Partner> listPartners(String status, String type) {
        if (status != null && type != null) {
            return partnerRepository.findAll().stream()
                    .filter(p -> p.getStatus().equals(status) && p.getPartnerType().equals(type))
                    .toList();
        }
        if (status != null) {
            return partnerRepository.findByStatus(status);
        }
        if (type != null) {
            return partnerRepository.findByPartnerType(type);
        }
        return partnerRepository.findAll();
    }

    @Transactional
    public Partner updatePartner(UUID id, UpdatePartnerRequest request) {
        Partner partner = findById(id);

        if (request.getCompanyName() != null) {
            partner.setCompanyName(request.getCompanyName());
        }
        if (request.getDisplayName() != null) {
            partner.setDisplayName(request.getDisplayName());
        }
        if (request.getIndustry() != null) {
            partner.setIndustry(request.getIndustry());
        }
        if (request.getWebsite() != null) {
            partner.setWebsite(request.getWebsite());
        }
        if (request.getLogoUrl() != null) {
            partner.setLogoUrl(request.getLogoUrl());
        }
        if (request.getPartnerType() != null) {
            partner.setPartnerType(request.getPartnerType());
        }
        if (request.getProtocolsEnabled() != null) {
            partner.setProtocolsEnabled(toJson(request.getProtocolsEnabled()));
        }
        if (request.getSlaTier() != null) {
            partner.setSlaTier(request.getSlaTier());
        }
        if (request.getMaxFileSizeBytes() != null) {
            partner.setMaxFileSizeBytes(request.getMaxFileSizeBytes());
        }
        if (request.getMaxTransfersPerDay() != null) {
            partner.setMaxTransfersPerDay(request.getMaxTransfersPerDay());
        }
        if (request.getRetentionDays() != null) {
            partner.setRetentionDays(request.getRetentionDays());
        }
        if (request.getNotes() != null) {
            partner.setNotes(request.getNotes());
        }

        partnerRepository.save(partner);
        log.info("Updated partner id={}", id);

        // Replace all contacts if provided
        if (request.getContacts() != null) {
            contactRepository.deleteByPartnerId(id);
            for (CreatePartnerRequest.ContactRequest cr : request.getContacts()) {
                PartnerContact contact = PartnerContact.builder()
                        .partner(partner)
                        .name(cr.getName())
                        .email(cr.getEmail())
                        .phone(cr.getPhone())
                        .role(cr.getRole() != null ? cr.getRole() : "Technical")
                        .isPrimary(cr.isPrimary())
                        .build();
                contactRepository.save(contact);
            }
        }

        return partner;
    }

    @Transactional
    public void deletePartner(UUID id) {
        Partner partner = findById(id);
        partner.setStatus("OFFBOARDED");
        partnerRepository.save(partner);
        log.info("Soft-deleted (offboarded) partner id={}", id);
    }

    @Transactional
    public Partner activatePartner(UUID id) {
        Partner partner = findById(id);
        partner.setStatus("ACTIVE");
        partner.setOnboardingPhase("LIVE");
        partnerRepository.save(partner);
        log.info("Activated partner id={}", id);
        return partner;
    }

    @Transactional
    public Partner suspendPartner(UUID id) {
        Partner partner = findById(id);
        partner.setStatus("SUSPENDED");
        partnerRepository.save(partner);
        log.info("Suspended partner id={}", id);
        return partner;
    }

    public Map<String, Long> getPartnerStats() {
        List<Partner> all = partnerRepository.findAll();
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("total", (long) all.size());
        stats.put("PENDING", all.stream().filter(p -> "PENDING".equals(p.getStatus())).count());
        stats.put("ACTIVE", all.stream().filter(p -> "ACTIVE".equals(p.getStatus())).count());
        stats.put("SUSPENDED", all.stream().filter(p -> "SUSPENDED".equals(p.getStatus())).count());
        stats.put("OFFBOARDED", all.stream().filter(p -> "OFFBOARDED".equals(p.getStatus())).count());
        return stats;
    }

    public List<TransferAccount> getPartnerAccounts(UUID partnerId) {
        findById(partnerId); // ensure partner exists
        return accountRepository.findByPartnerId(partnerId);
    }

    public List<FileFlow> getPartnerFlows(UUID partnerId) {
        findById(partnerId);
        return fileFlowRepository.findByPartnerId(partnerId);
    }

    public List<DeliveryEndpoint> getPartnerEndpoints(UUID partnerId) {
        findById(partnerId);
        return deliveryEndpointRepository.findByPartnerId(partnerId);
    }

    @Transactional
    public TransferAccount linkAccountToPartner(UUID partnerId, UUID accountId) {
        findById(partnerId); // ensure partner exists
        TransferAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NoSuchElementException("Account not found: " + accountId));
        account.setPartnerId(partnerId);
        accountRepository.save(account);
        log.info("Linked account id={} to partner id={}", accountId, partnerId);
        return account;
    }

    private Partner findById(UUID id) {
        return partnerRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Partner not found: " + id));
    }

    private String generateSlug(String companyName) {
        return companyName.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize protocols list, defaulting to empty array", e);
            return "[]";
        }
    }
}
