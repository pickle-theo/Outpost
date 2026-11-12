package com.outpost.sample;

import com.outpost.core.annotation.OutboxCache;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注解式用法示例：业务方法成功返回后（事务提交前）注册缓存失效消息。
 *
 * <p>真实接入时把注释处替换为业务 SQL；关键是业务 SQL 与消息表 INSERT
 * 必须共用同一个 DataSource、同一个事务。</p>
 */
@Service
public class SampleOrderService {

    @Transactional
    @OutboxCache(namespace = "order", key = "'order:' + #orderId", doubleDeleteDelayMills = 1000)
    public void updateOrder(String orderId) {
        if (orderId == null || orderId.trim().isEmpty()) {
            throw new IllegalArgumentException("orderId 不能为空");
        }
        // jdbcTemplate.update("UPDATE orders SET status = ? WHERE id = ?", status, orderId);
    }
}
