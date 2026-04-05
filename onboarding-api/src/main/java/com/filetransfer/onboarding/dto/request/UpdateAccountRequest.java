package com.filetransfer.onboarding.dto.request;

import lombok.Data;

import java.util.Map;

@Data
public class UpdateAccountRequest {
    private Boolean active;
    private String newPassword;
    private String publicKey;
    private Map<String, Boolean> permissions;
    private String serverInstance;
}
