package com.outpost.starter.cache;

import com.outpost.core.cache.CacheBizTypes;
import com.outpost.core.config.OutpostProperties;
import com.outpost.core.delivery.DeliveryHandler;
import com.outpost.core.delivery.OutboxDeliveryMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;

/**
 * 内置 {@code cache_tag_delete} 处理器：按 rockscache 协议对缓存 key 执行 TagAsDeleted。
 *
 * <p>脚本只做 {@code lockUntil=0}、移除 {@code lockOwner}、按延迟时间续期，不删除 value；
 * 读侧随后返回旧值并异步回源。每 key 一条消息，不在此处做批量跨 key 操作。</p>
 */
public class CacheTagDeleteHandler implements DeliveryHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CacheTagDeleteHandler.class);

    private final StringRedisTemplate redis;
    private final TimeoutRedisExecutor timeoutExecutor;
    private final OutpostProperties properties;

    public CacheTagDeleteHandler(StringRedisTemplate redis,
                                 TimeoutRedisExecutor timeoutExecutor,
                                 OutpostProperties properties) {
        if (redis == null) {
            throw new IllegalArgumentException("redis 不能为 null");
        }
        if (timeoutExecutor == null) {
            throw new IllegalArgumentException("timeoutExecutor 不能为 null");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties 不能为 null");
        }
        this.redis = redis;
        this.timeoutExecutor = timeoutExecutor;
        this.properties = properties;
    }

    @Override
    public String getBizType() {
        return CacheBizTypes.CACHE_TAG_DELETE;
    }

    @Override
    public void deliver(OutboxDeliveryMessage message) throws Exception {
        final String key = message.getPayload();
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "cache_tag_delete 的 payload 为空，无法确定缓存 key, messageId=" + message.getId());
        }
        final int delaySeconds = Math.max(1, properties.getDeliveryCache().getTag().getDelaySeconds());
        timeoutExecutor.execute(
                () -> redis.execute(RockscacheScripts.TAG_DELETE,
                        Collections.singletonList(key), String.valueOf(delaySeconds)),
                "TAG_DELETE " + key);
        LOG.debug("Outpost 缓存标记删除完成，key={}, delaySeconds={}, messageId={}",
                key, delaySeconds, message.getId());
    }
}
