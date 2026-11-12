package com.outpost.core.cache;

import com.outpost.core.config.OutpostProperties;
import com.outpost.core.produce.OutpostTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 基于本地消息表的缓存失效执行器，按 {@code delivery-cache.enabled-namespaces} 灰度路由。
 *
 * <p>白名单为空表示全部走消息表；白名单非空时，未命中的 namespace 回落到同步直删，
 * 保证灰度开关不会表现为「静默不删」。</p>
 */
public class OutboxCacheInvalidator implements CacheInvalidator {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxCacheInvalidator.class);

    private final OutpostTemplate template;
    private final CacheInvalidator directFallback;
    private final OutpostProperties properties;

    public OutboxCacheInvalidator(OutpostTemplate template,
                                  CacheInvalidator directFallback,
                                  OutpostProperties properties) {
        if (template == null) {
            throw new IllegalArgumentException("template 不能为 null");
        }
        if (directFallback == null) {
            throw new IllegalArgumentException("directFallback 不能为 null");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties 不能为 null");
        }
        this.template = template;
        this.directFallback = directFallback;
        this.properties = properties;
    }

    @Override
    public void invalidate(String namespace, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        List<String> enabledNamespaces = properties.getDeliveryCache().getEnabledNamespaces();
        if (enabledNamespaces == null || enabledNamespaces.isEmpty()
                || enabledNamespaces.contains(namespace)) {
            template.registerCacheInvalidate(namespace, keys);
            return;
        }
        LOG.warn("Outpost 缓存失效 namespace 不在灰度白名单，已回落同步直删，namespace={}, keyCount={}",
                namespace, keys.size());
        directFallback.invalidate(namespace, keys);
    }
}
