package com.filetransfer.onboarding.dto.response;

import com.filetransfer.shared.entity.core.Partner;
import com.filetransfer.shared.entity.integration.PartnerContact;
import com.filetransfer.shared.entity.core.TransferAccount;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PartnerDetailResponse {
    private Partner partner;
    private List<PartnerContact> contacts;
    private long accountCount;
    private long flowCount;
    private long endpointCount;
    private List<TransferAccount> accounts;
}
