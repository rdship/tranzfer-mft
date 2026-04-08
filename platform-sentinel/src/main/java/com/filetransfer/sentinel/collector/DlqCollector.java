package com.filetransfer.sentinel.collector;

import com.filetransfer.shared.entity.DeadLetterMessage;
import com.filetransfer.shared.repository.DeadLetterMessageRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DlqCollector {

    private final DeadLetterMessageRepository dlqRepository;

    @Getter
    private volatile long pendingCount = 0;

    @Getter
    private volatile List<DeadLetterMessage> pendingMessages = List.of();

    public void collect() {
        try {
            var page = dlqRepository.findByStatus(DeadLetterMessage.Status.PENDING, PageRequest.of(0, 100));
            pendingMessages = page.getContent();
            pendingCount = page.getTotalElements();
            log.debug("DlqCollector: {} pending DLQ messages", pendingCount);
        } catch (Exception e) {
            log.warn("DlqCollector failed: {}", e.getMessage());
        }
    }
}
