package com.outpost.starter.cache;

import com.outpost.core.cache.CacheBizTypes;
import com.outpost.core.delivery.DeliveryHandler;
import com.outpost.core.delivery.OutboxDeliveryMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;

/**
 * 内置 {@code cache_delete} 处理器：物理删除 Redis key，天然幂等。
 *
 * <p>{@code payload} 即缓存 key，{@code bizKey} 仅用于标识与观测。key 不存在时 DEL 返回 0
 * 同样视为成功。</p>
 */
public class CacheDeleteHandler implements DeliveryHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CacheDeleteHandler.class);

    private final StringRedisTemplate redis;
    private final TimeoutRedisExecutor timeoutExecutor;

    public CacheDeleteHandler(StringRedisTemplate redis, TimeoutRedisExecutor timeoutExecutor) {
        if (redis == null) {
            throw new IllegalArgumentException("redis 不能为 null");
        }
        if (timeoutExecutor == null) {
            throw new IllegalArgumentException("timeoutExecutor 不能为 null");
        }
        this.redis = redis;
        this.timeoutExecutor = timeoutExecutor;
    }

    @Override
    public String getBizType() {
        return CacheBizTypes.CACHE_DELETE;
    }

    @Override
    public void deliver(OutboxDeliveryMessage message) throws Exception {
        final String key = message.getPayload();
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "cache_delete 的 payload 为空，无法确定缓存 key, messageId=" + message.getId());
        }
        Long deleted = timeoutExecutor.execute(
                () -> redis.delete(Collections.singletonList(key)), "DEL " + key);
        LOG.debug("Outpost 缓存删除完成，key={}, deleted={}, messageId={}",
                key, deleted, message.getId());
    }
}
