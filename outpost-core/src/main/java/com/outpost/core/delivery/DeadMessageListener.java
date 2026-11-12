package com.outpost.core.delivery;

import com.outpost.core.message.DeadReason;

/**
 * 死信监听器：消息进入 DEAD 时回调。
 */
public interface DeadMessageListener {

    /**
     * @param message 死信消息
     * @param reason  死信原因
     * @param cause   导致死信的异常，可能为 null
     */
    void onDead(OutboxDeliveryMessage message, DeadReason reason, Throwable cause);
}
