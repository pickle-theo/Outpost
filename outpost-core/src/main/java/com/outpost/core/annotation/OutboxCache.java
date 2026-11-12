package com.outpost.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 业务方法成功返回后注册缓存失效消息。
 *
 * <p>被标注的方法必须运行在 Spring 事务上下文中，否则注册阶段直接抛异常。</p>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OutboxCache {

    /**
     * 业务域，用于灰度白名单与统计。
     */
    String namespace();

    /**
     * 单个缓存 key 的 SpEL 表达式，求值结果必须是 {@link String}。
     * 与 {@link #keys()} 必须且只能配置一个。
     */
    String key() default "";

    /**
     * 批量缓存 key 的 SpEL 表达式，求值结果必须是 {@code List<String>}。
     * 与 {@link #key()} 必须且只能配置一个。
     */
    String keys() default "";

    /**
     * 第二次删除的延迟毫秒数，-1 表示使用全局配置 {@code outpost.delivery-cache.double-delete.delay-millis}；
     * 非负时按方法粒度覆盖全局延迟。tag 模式下双删被强制关闭，本字段不生效。
     */
    long doubleDeleteDelayMills() default -1;
}
