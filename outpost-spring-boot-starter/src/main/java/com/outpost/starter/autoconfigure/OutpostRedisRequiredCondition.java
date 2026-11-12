package com.outpost.starter.autoconfigure;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 仅在确实需要 Redis 时才装配 SDK 自带的 {@code StringRedisTemplate}。
 */
public class OutpostRedisRequiredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        if (OutpostRedisRequirement.hasCustomLock(context.getBeanFactory())) {
            return false;
        }
        return OutpostRedisRequirement.isRequired(context.getEnvironment());
    }
}
