package com.outpost.core.config;

/**
 * 配置非法导致启动失败时抛出。
 */
public class OutpostConfigurationException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public OutpostConfigurationException(String message) {
        super(message);
    }

    public OutpostConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
