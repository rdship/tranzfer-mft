package com.filetransfer.shared.dto;

import com.filetransfer.shared.enums.Protocol;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountCreatedEvent {
    private String eventType = "account.created";
    private UUID accountId;
    private Protocol protocol;
    private String username;
    private String homeDir;
    private String serverInstance;
}
