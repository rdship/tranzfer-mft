package com.filetransfer.shared.entity.integration;

import com.filetransfer.shared.entity.core.*;
import com.filetransfer.shared.entity.transfer.*;

import com.filetransfer.shared.entity.Auditable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Contact person associated with a partner organization.
 */
@Entity
@Table(name = "partner_contacts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartnerContact extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partner_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Partner partner;

    @Column(nullable = false)
    private String name;

    private String email;

    @Column(length = 50)
    private String phone;

    @Column(length = 100, nullable = false)
    @Builder.Default
    private String role = "Technical";

    @Column(nullable = false)
    @Builder.Default
    private Boolean isPrimary = false;

}
