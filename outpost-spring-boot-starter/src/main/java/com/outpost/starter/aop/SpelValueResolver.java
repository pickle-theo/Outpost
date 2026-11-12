package com.outpost.starter.aop;

import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionException;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基于 Spring Expression Language 的注解属性求值器。
 *
 * <p>支持按参数名（如 {@code #orderId}）与位置（如 {@code #p0}、{@code #a0}）引用方法参数，
 * {@code #root} 指向被代理的目标对象。</p>
 *
 * <p>求值结果类型严格校验：不做任何隐式 {@code toString()} 转换，
 * 避免把 {@code com.foo.User@1a2b3c} 这类无意义文本静默写入消息表。</p>
 */
public class SpelValueResolver {

    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * 求值表达式，返回原始结果（可能为 null）。
     */
    public Object evaluate(String expression, Method method, Object[] args, Object target) {
        if (expression == null || expression.trim().isEmpty()) {
            throw new IllegalArgumentException("SpEL 表达式不能为空");
        }
        Object[] arguments = args == null ? new Object[0] : args;
        EvaluationContext context =
                new MethodBasedEvaluationContext(target, method, arguments, parameterNameDiscoverer);
        try {
            return expressionParser.parseExpression(expression).getValue(context);
        } catch (ExpressionException ex) {
            throw new IllegalArgumentException("SpEL 表达式求值失败: " + expression
                    + "，原因: " + ex.getMessage(), ex);
        }
    }

    /**
     * 求值并要求结果为 {@link String} 或 null。
     *
     * @throws IllegalArgumentException 结果不是 {@link String}
     */
    public String resolveString(String expression, Method method, Object[] args, Object target) {
        Object value = evaluate(expression, method, args, target);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("SpEL 表达式 [" + expression
                    + "] 的求值结果必须为 String，实际类型: " + value.getClass().getName()
                    + "，请由调用方自行序列化");
        }
        return (String) value;
    }

    /**
     * 求值并要求结果为 {@code List<String>} 或 null。
     *
     * @throws IllegalArgumentException 结果不是 {@link List}，或存在非 {@link String} 元素
     */
    public List<String> resolveStringList(String expression, Method method, Object[] args, Object target) {
        Object value = evaluate(expression, method, args, target);
        if (value == null) {
            return Collections.emptyList();
        }
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("SpEL 表达式 [" + expression
                    + "] 的求值结果必须为 java.util.List，实际类型: " + value.getClass().getName());
        }
        List<?> rawList = (List<?>) value;
        List<String> result = new ArrayList<String>(rawList.size());
        for (Object element : rawList) {
            if (!(element instanceof String)) {
                throw new IllegalArgumentException("SpEL 表达式 [" + expression
                        + "] 的 List 元素必须为 String，实际元素类型: "
                        + (element == null ? "null" : element.getClass().getName()));
            }
            result.add((String) element);
        }
        return result;
    }
}
