package com.outpost.sample;

import com.outpost.core.delivery.DeliveryHandler;
import com.outpost.core.delivery.OutboxDeliveryMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 通用业务消息示例：bizType 由业务自定义，与缓存处理器无关。
 *
 * <p>真实场景里这里会发送 MQ 事件或调用外部接口；示例用内存列表记录投递结果，
 * 便于集成测试断言。生产实现必须以 bizKey 做幂等去重。</p>
 */
@Component
public class OrderPaidHandler implements DeliveryHandler {

    public static final String BIZ_TYPE = "order_paid";

    private final CopyOnWriteArrayList<String> delivered = new CopyOnWriteArrayList<String>();

    @Override
    public String getBizType() {
        return BIZ_TYPE;
    }

    @Override
    public void deliver(OutboxDeliveryMessage message) {
        delivered.add(message.getBizKey());
    }

    @Override
    public long getMaxExecutionMillis() {
        return 1000L;
    }

    public List<String> delivered() {
        return delivered;
    }

    public void clear() {
        delivered.clear();
    }
}
