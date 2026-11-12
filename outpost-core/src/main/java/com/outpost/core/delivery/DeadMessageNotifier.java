package com.outpost.core.delivery;

import com.outpost.core.message.DeadReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 死信通知器：逐个回调监听器，单个监听器异常完全隔离。
 */
public final class DeadMessageNotifier {

    private static final Logger log = LoggerFactory.getLogger(DeadMessageNotifier.class);

    private final List<DeadMessageListener> listeners;

    public DeadMessageNotifier(List<DeadMessageListener> listeners) {
        if (listeners == null || listeners.isEmpty()) {
            this.listeners = Collections.emptyList();
        } else {
            this.listeners = Collections.unmodifiableList(new ArrayList<DeadMessageListener>(listeners));
        }
    }

    public void notifyDead(OutboxDeliveryMessage message, DeadReason reason, Throwable cause) {
        if (listeners.isEmpty()) {
            log.debug("未注册 DeadMessageListener，跳过死信通知，messageId={}, reason={}",
                    message == null ? null : message.getId(), reason);
            return;
        }
        for (DeadMessageListener listener : listeners) {
            try {
                listener.onDead(message, reason, cause);
            } catch (Throwable t) {
                log.error("死信监听器执行失败已隔离，messageId={}, reason={}, listener={}",
                        message == null ? null : message.getId(),
                        reason,
                        listener == null ? "null" : listener.getClass().getName(),
                        t);
            }
        }
    }
}
