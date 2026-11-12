package com.outpost.starter.autoconfigure;

import com.outpost.core.config.OutpostProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 配置绑定适配器：{@link OutpostProperties} 位于 core，不能依赖 Spring Boot，
 * 因此由 starter 侧的子类承担 {@code @ConfigurationProperties} 绑定。
 *
 * <p>嵌套配置组（schedule/retry/delivery-cache/tag/double-delete/cleanup）继承父类
 * getter/setter，Boot 的 relaxed binding 可直接绑定如
 * {@code outpost.schedule.fixed-delay-ms}、{@code outpost.delivery-cache.tag.read-client}。</p>
 */
@ConfigurationProperties(prefix = "outpost")
public class OutpostConfigurationProperties extends OutpostProperties {
}
