package com.outpost.starter.delivery;

import com.outpost.core.delivery.DeadMessageListener;
import com.outpost.core.delivery.OutboxDeliveryMessage;
import com.outpost.core.message.DeadReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内置死信日志监听器：按 dead_reason 分流到运维通道 / 业务通道。
 */
public class LoggingDeadMessageListener implements DeadMessageListener {

    private static final Logger OPERATIONS_LOG = LoggerFactory.getLogger("com.outpost.dead.operations");
    private static final Logger BUSINESS_LOG = LoggerFactory.getLogger("com.outpost.dead.business");

    private static final int MAX_CAUSE_LENGTH = 512;

    @Override
    public void onDead(OutboxDeliveryMessage message, DeadReason reason, Throwable cause) {
        boolean businessChannel = reason == DeadReason.UNRECOVERABLE;
        Logger logger = businessChannel ? BUSINESS_LOG : OPERATIONS_LOG;
        String channel = businessChannel ? "业务通道" : "运维通道";

        Long messageId = message == null ? null : message.getId();
        logger.warn("[{}] 死信触发 reason={}, messageId={}, bizType={}, bizKey={}, cause={}",
                channel,
                reason,
                messageId,
                message == null ? null : message.getBizType(),
                message == null ? null : message.getBizKey(),
                summarize(cause));
        if (logger.isDebugEnabled()) {
            logger.debug("[{}] 死信完整堆栈 messageId={}, reason={}", channel, messageId, reason, cause);
        }
    }

    private static String summarize(Throwable cause) {
        if (cause == null) {
            return "无";
        }
        String message = cause.getMessage();
        String summary = cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        if (summary.length() > MAX_CAUSE_LENGTH) {
            summary = summary.substring(0, MAX_CAUSE_LENGTH) + "...";
        }
        return summary;
    }
}
