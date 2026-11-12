package com.outpost.core.admin;

import com.outpost.core.message.MessageStatus;

/**
 * 死信查询条件：固定 {@code status=DEAD}，可按 {@code dead_reason} 细分。
 */
public class DeadMessageQuery extends MessageQuery {

    public DeadMessageQuery() {
        setStatus(MessageStatus.DEAD.code());
    }

    @Override
    public void setStatus(Integer status) {
        if (status != null && status != MessageStatus.DEAD.code()) {
            throw new IllegalArgumentException("死信查询的 status 必须为 DEAD(4)，实际值: " + status);
        }
        super.setStatus(status);
    }
}
