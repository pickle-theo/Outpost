package com.outpost.starter.aop;

import com.outpost.core.annotation.OutboxCache;
import com.outpost.core.annotation.OutboxMessage;
import com.outpost.core.produce.OutpostTemplate;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * {@link OutboxMessage} / {@link OutboxCache} 注解切面。
 *
 * <p>使用 {@link Ordered#LOWEST_PRECEDENCE}，尽量位于 {@code @Transactional} 通知的内层，
 * 从而在事务仍处于活跃状态、业务方法成功返回后注册消息。业务方法抛异常时
 * {@code @AfterReturning} 不会触发，消息不会被注册。</p>
 *
 * <p><b>事务前提：</b>本切面不自行开启事务。被注解的方法必须位于 Spring 事务内，
 * 否则 {@link OutpostTemplate} 会抛出 {@link IllegalStateException}，提示接入方添加 {@code @Transactional}。</p>
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE)
public class OutboxMessageAspect {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxMessageAspect.class);

    private final OutpostTemplate outpostTemplate;
    private final SpelValueResolver spelValueResolver;

    public OutboxMessageAspect(OutpostTemplate outpostTemplate, SpelValueResolver spelValueResolver) {
        if (outpostTemplate == null) {
            throw new IllegalArgumentException("outpostTemplate 不能为 null");
        }
        if (spelValueResolver == null) {
            throw new IllegalArgumentException("spelValueResolver 不能为 null");
        }
        this.outpostTemplate = outpostTemplate;
        this.spelValueResolver = spelValueResolver;
    }

    @AfterReturning(pointcut = "@annotation(com.outpost.core.annotation.OutboxMessage)", returning = "ret")
    public void afterReturningOutboxMessage(JoinPoint joinPoint, Object ret) {
        OutboxMessage annotation = findAnnotation(joinPoint, OutboxMessage.class);
        if (annotation == null) {
            return;
        }
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object[] args = joinPoint.getArgs();
        Object target = joinPoint.getTarget();

        String bizKey = spelValueResolver.resolveString(annotation.bizKey(), method, args, target);
        if (bizKey == null) {
            throw new IllegalArgumentException("@OutboxMessage bizKey 求值结果为 null，biz_key 不允许为空，方法: "
                    + method);
        }
        String payload = null;
        if (annotation.payload() != null && !annotation.payload().trim().isEmpty()) {
            payload = spelValueResolver.resolveString(annotation.payload(), method, args, target);
        }

        if (annotation.delayMills() > 0) {
            outpostTemplate.registerDelayedMessage(annotation.bizType(), bizKey, payload,
                    annotation.delayMills());
        } else {
            outpostTemplate.registerMessage(annotation.bizType(), bizKey, payload);
        }
    }

    @AfterReturning(pointcut = "@annotation(com.outpost.core.annotation.OutboxCache)")
    public void afterReturningOutboxCache(JoinPoint joinPoint) {
        OutboxCache annotation = findAnnotation(joinPoint, OutboxCache.class);
        if (annotation == null) {
            return;
        }
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object[] args = joinPoint.getArgs();
        Object target = joinPoint.getTarget();

        String keyExpression = annotation.key();
        String keysExpression = annotation.keys();
        boolean hasKey = keyExpression != null && !keyExpression.trim().isEmpty();
        boolean hasKeys = keysExpression != null && !keysExpression.trim().isEmpty();
        if (hasKey == hasKeys) {
            throw new IllegalArgumentException("@OutboxCache 的 key 与 keys 必须且只能配置一个，方法: " + method);
        }
        if (annotation.doubleDeleteDelayMills() < -1L) {
            throw new IllegalArgumentException("@OutboxCache doubleDeleteDelayMills 仅允许 -1 或非负数，实际值: "
                    + annotation.doubleDeleteDelayMills() + "，方法: " + method);
        }

        List<String> keys;
        if (hasKey) {
            String key = spelValueResolver.resolveString(keyExpression, method, args, target);
            if (key == null || key.trim().isEmpty()) {
                LOG.debug("@OutboxCache key 求值结果为空，跳过注册，方法: {}", method);
                return;
            }
            keys = Collections.singletonList(key);
        } else {
            keys = spelValueResolver.resolveStringList(keysExpression, method, args, target);
            if (keys.isEmpty()) {
                LOG.debug("@OutboxCache keys 求值结果为空列表，跳过注册，方法: {}", method);
                return;
            }
        }
        outpostTemplate.registerCacheInvalidate(annotation.namespace(), keys,
                annotation.doubleDeleteDelayMills());
    }

    private static <A extends Annotation> A findAnnotation(JoinPoint joinPoint, Class<A> annotationType) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        A annotation = AnnotatedElementUtils.findMergedAnnotation(method, annotationType);
        if (annotation == null && joinPoint.getTarget() != null) {
            Method specificMethod = AopUtils.getMostSpecificMethod(method, joinPoint.getTarget().getClass());
            annotation = AnnotatedElementUtils.findMergedAnnotation(specificMethod, annotationType);
        }
        return annotation;
    }
}
