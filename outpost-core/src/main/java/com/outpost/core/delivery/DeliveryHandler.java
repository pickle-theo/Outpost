package com.outpost.core.delivery;

/**
 * 投递处理器 SPI。
 *
 * <p>bizType 全局唯一，与消息的 bizType 一一对应；处理器实现必须保证动作天然幂等。</p>
 */
public interface DeliveryHandler {

    /**
     * @return 处理器类型，全局唯一，与消息的 bizType 一一对应
     */
    String getBizType();

    /**
     * 执行投递动作。
     *
     * <p>抛出 {@link UnrecoverableDeliveryException} 表示永远不可能成功，直接进死信；
     * 抛出其他异常表示暂时性失败，走指数退避重试。</p>
     */
    void deliver(OutboxDeliveryMessage message) throws Exception;

    /**
     * @return 单条消息的最大执行耗时（毫秒），用于启动期超时与耦合公式校验
     */
    default long getMaxExecutionMillis() {
        return 1000L;
    }
}
