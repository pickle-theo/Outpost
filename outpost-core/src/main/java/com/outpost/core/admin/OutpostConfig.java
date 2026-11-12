package com.outpost.core.admin;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 运维热更配置：仅白名单内的键允许变更。
 *
 * <p>使用「键 → 值」形式，便于 {@code OutpostAdminService#updateConfig} 精确判断
 * 调用方是否试图修改不允许热更的项，并给出明确提示。</p>
 */
public final class OutpostConfig {

    public static final String BACKOFF_STEPS = "retry.backoff-steps";
    public static final String ENABLED_NAMESPACES = "delivery-cache.enabled-namespaces";
    public static final String CLEANUP_ENABLED = "cleanup.enabled";
    public static final String DOUBLE_DELETE_ENABLED = "delivery-cache.double-delete.enabled";
    public static final String DOUBLE_DELETE_DELAY_MILLIS = "delivery-cache.double-delete.delay-millis";
    public static final String BATCH_SIZE = "schedule.batch-size";

    private static final Set<String> SUPPORTED_KEYS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    BACKOFF_STEPS, ENABLED_NAMESPACES, CLEANUP_ENABLED,
                    DOUBLE_DELETE_ENABLED, DOUBLE_DELETE_DELAY_MILLIS, BATCH_SIZE)));

    private final Map<String, Object> changes = new LinkedHashMap<String, Object>();

    public static Set<String> supportedKeys() {
        return SUPPORTED_KEYS;
    }

    public OutpostConfig set(String key, Object value) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("热更配置项 key 不能为空");
        }
        changes.put(key, value);
        return this;
    }

    public Object get(String key) {
        return changes.get(key);
    }

    public boolean contains(String key) {
        return changes.containsKey(key);
    }

    public Map<String, Object> getChanges() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(changes));
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }
}
