package com.filetransfer.shared.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountUpdatedEvent {
    private String eventType = "account.updated";
    private UUID accountId;
    private String username;
    private Boolean active;

    // QoS fields for live propagation to protocol services
    private Long qosUploadBytesPerSecond;
    private Long qosDownloadBytesPerSecond;
    private Integer qosMaxConcurrentSessions;
    private Integer qosPriority;
    private Integer qosBurstAllowancePercent;
}
