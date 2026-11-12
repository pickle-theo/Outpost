package com.outpost.core.admin;

import com.outpost.core.delivery.OutboxDeliveryMessage;

import java.util.List;

/**
 * 运维管理 Java API（不内置 HTTP 端点）。
 */
public interface OutpostAdminService {

    OutpostPage<OutboxDeliveryMessage> queryMessages(MessageQuery query);

    OutpostPage<OutboxDeliveryMessage> queryDeadMessages(DeadMessageQuery query);

    void resendMessage(Long messageId);

    void batchResendMessages(List<Long> messageIds);

    OutpostStats getStats();

    void updateConfig(OutpostConfig config);
}
