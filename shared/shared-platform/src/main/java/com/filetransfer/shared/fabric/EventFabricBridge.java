package com.filetransfer.shared.fabric;

import com.filetransfer.fabric.FabricClient;
import com.filetransfer.fabric.config.FabricProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Bridge for cross-service events (account, flow rule, notification).
 * Separate from FlowFabricBridge which handles flow execution work items.
 *
 * Topics:
 * - events.account       — account.created, account.updated, account.deleted
 * - events.flow-rule     — flow rule changes (hot-reload signal)
 * - events.notification  — notification dispatch events
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventFabricBridge {

    public static final String TOPIC_EVENTS_ACCOUNT = "events.account";
    public static final String TOPIC_EVENTS_FLOW_RULE = "events.flow-rule";
    public static final String TOPIC_EVENTS_NOTIFICATION = "events.notification";

    private final FabricClient fabricClient;
    private final FabricProperties properties;

    public boolean isAccountPublishActive() {
        return properties.isEnabled() && properties.getEvents().isAccountPublish() && fabricClient.isDistributed();
    }

    public boolean isAccountConsumeActive() {
        return properties.isEnabled() && properties.getEvents().isAccountConsume() && fabricClient.isDistributed();
    }

    public boolean isFlowRulePublishActive() {
        return properties.isEnabled() && properties.getEvents().isFlowRulePublish() && fabricClient.isDistributed();
    }

    public boolean isFlowRuleConsumeActive() {
        return properties.isEnabled() && properties.getEvents().isFlowRuleConsume() && fabricClient.isDistributed();
    }

    public boolean isNotificationPublishActive() {
        return properties.isEnabled() && properties.getEvents().isNotificationPublish() && fabricClient.isDistributed();
    }

    public boolean isNotificationConsumeActive() {
        return properties.isEnabled() && properties.getEvents().isNotificationConsume() && fabricClient.isDistributed();
    }

    /** Publish an account event. Partition key = account username. */
    public void publishAccountEvent(String username, Object payload) {
        if (!isAccountPublishActive()) return;
        try {
            fabricClient.publish(TOPIC_EVENTS_ACCOUNT, username, payload);
        } catch (Exception e) {
            log.warn("[EventFabricBridge] Failed to publish account event: {}", e.getMessage());
        }
    }

    /** Publish a flow rule event. Partition key = flow id. */
    public void publishFlowRuleEvent(String flowId, Object payload) {
        if (!isFlowRulePublishActive()) return;
        try {
            fabricClient.publish(TOPIC_EVENTS_FLOW_RULE, flowId, payload);
        } catch (Exception e) {
            log.warn("[EventFabricBridge] Failed to publish flow rule event: {}", e.getMessage());
        }
    }

    /** Publish a notification event. Partition key = event type. */
    public void publishNotificationEvent(String eventType, Object payload) {
        if (!isNotificationPublishActive()) return;
        try {
            fabricClient.publish(TOPIC_EVENTS_NOTIFICATION, eventType, payload);
        } catch (Exception e) {
            log.warn("[EventFabricBridge] Failed to publish notification event: {}", e.getMessage());
        }
    }

    /** Subscribe to the account events topic. */
    public void subscribeAccountEvents(String groupId, FabricClient.MessageHandler handler) {
        if (!isAccountConsumeActive()) {
            log.info("[EventFabricBridge] Account event consumption disabled, not subscribing");
            return;
        }
        fabricClient.subscribe(TOPIC_EVENTS_ACCOUNT, groupId, handler);
    }

    /** Subscribe to the flow rule events topic. */
    public void subscribeFlowRuleEvents(String groupId, FabricClient.MessageHandler handler) {
        if (!isFlowRuleConsumeActive()) {
            log.info("[EventFabricBridge] Flow rule event consumption disabled, not subscribing");
            return;
        }
        fabricClient.subscribe(TOPIC_EVENTS_FLOW_RULE, groupId, handler);
    }

    /** Subscribe to the notification events topic. */
    public void subscribeNotificationEvents(String groupId, FabricClient.MessageHandler handler) {
        if (!isNotificationConsumeActive()) {
            log.info("[EventFabricBridge] Notification event consumption disabled, not subscribing");
            return;
        }
        fabricClient.subscribe(TOPIC_EVENTS_NOTIFICATION, groupId, handler);
    }
}
