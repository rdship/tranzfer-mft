package com.filetransfer.onboarding.dto.request;

import com.filetransfer.shared.enums.Protocol;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

@Data
public class CreateAccountRequest {
    @NotNull
    private Protocol protocol;

    @NotBlank
    @Size(min = 3, max = 64)
    private String username;

    @NotBlank
    @Size(min = 8)
    private String password;

    // Optional SSH public key for SFTP key-based auth
    private String publicKey;

    // Optional: {"read": true, "write": true, "delete": false}
    private Map<String, Boolean> permissions;
}
