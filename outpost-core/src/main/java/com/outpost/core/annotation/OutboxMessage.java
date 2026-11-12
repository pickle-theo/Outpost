package com.outpost.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 业务方法成功返回后注册一条本地消息。
 *
 * <p>被标注的方法必须运行在 Spring 事务上下文中（例如外层 {@code @Transactional}），
 * 否则注册阶段直接抛异常。</p>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OutboxMessage {

    /**
     * 业务类型，对应 {@code DeliveryHandler#getBizType()}。
     */
    String bizType();

    /**
     * 业务唯一键，SpEL 表达式，求值结果必须是 {@link String} 且非 null。
     */
    String bizKey();

    /**
     * 消息体，SpEL 表达式，求值结果必须是 {@link String} 或 null；留空表示 null。
     */
    String payload() default "";

    /**
     * 延迟投递毫秒数。
     */
    long delayMills() default 0;
}
