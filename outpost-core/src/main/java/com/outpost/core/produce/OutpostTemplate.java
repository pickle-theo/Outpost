package com.outpost.core.produce;

import java.util.List;
import java.util.function.Supplier;

/**
 * 生产路径编程式 API。
 *
 * <p>所有注册方法都必须运行在 Spring 事务上下文中，且该事务必须与 Outpost 使用的
 * {@code DataSource} 绑定；否则直接抛出 {@link IllegalStateException}。
 * 本接口不允许「自动开启新事务插入消息」，避免出现业务与消息不同事务的静默错误。</p>
 */
public interface OutpostTemplate {

    /**
     * 在事务中执行业务逻辑，可注册多条消息。
     */
    <T> T executeInTransaction(Supplier<T> businessAction);

    /**
     * 注册通用消息，payload 必须由调用方完成序列化。
     */
    void registerMessage(String bizType, String bizKey, String payload);

    /**
     * 注册延迟消息。
     */
    void registerDelayedMessage(String bizType, String bizKey, String payload, long delayMills);

    /**
     * 缓存场景便捷方法：展开每个 key 注册消息，开启双删时每个 key 额外注册一条辅助消息。
     */
    void registerCacheInvalidate(String namespace, List<String> cacheKeys);

    /**
     * 缓存场景便捷方法（可覆盖注解级别的双删延迟）。
     *
     * @param doubleDeleteDelayMills 第二次删除延迟毫秒数，负数表示使用全局配置
     */
    default void registerCacheInvalidate(String namespace, List<String> cacheKeys,
                                         long doubleDeleteDelayMills) {
        registerCacheInvalidate(namespace, cacheKeys);
    }
}
