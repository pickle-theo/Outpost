package com.outpost.core.delivery;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 处理器注册表：bizType 全局唯一，重复注册在启动期直接失败。
 */
public final class DeliveryHandlerRegistry {

    private final Map<String, DeliveryHandler> handlerByBizType;
    private final Set<String> bizTypes;

    public DeliveryHandlerRegistry(List<DeliveryHandler> handlers) {
        this.handlerByBizType = new LinkedHashMap<String, DeliveryHandler>();
        if (handlers != null) {
            for (DeliveryHandler handler : handlers) {
                if (handler == null) {
                    throw new IllegalStateException("DeliveryHandler 不能为 null，请检查 Spring Bean 装配");
                }
                String bizType = handler.getBizType();
                if (bizType == null || bizType.trim().isEmpty()) {
                    throw new IllegalStateException(
                            "DeliveryHandler bizType 不能为空，实现类: " + handler.getClass().getName());
                }
                DeliveryHandler existing = handlerByBizType.get(bizType);
                if (existing != null) {
                    throw new IllegalStateException(
                            "重复的 DeliveryHandler bizType: " + bizType
                                    + "，冲突实现类: " + existing.getClass().getName()
                                    + " 与 " + handler.getClass().getName()
                                    + "（bizType 必须全局唯一）");
                }
                handlerByBizType.put(bizType, handler);
            }
        }
        this.bizTypes = Collections.unmodifiableSet(
                new LinkedHashSet<String>(handlerByBizType.keySet()));
    }

    public Optional<DeliveryHandler> find(String bizType) {
        return Optional.ofNullable(handlerByBizType.get(bizType));
    }

    public DeliveryHandler getRequired(String bizType) {
        DeliveryHandler handler = handlerByBizType.get(bizType);
        if (handler == null) {
            throw new IllegalStateException("未找到 bizType 为 " + bizType + " 的 DeliveryHandler");
        }
        return handler;
    }

    public Collection<DeliveryHandler> all() {
        return Collections.unmodifiableCollection(handlerByBizType.values());
    }

    public Set<String> bizTypes() {
        return bizTypes;
    }
}
