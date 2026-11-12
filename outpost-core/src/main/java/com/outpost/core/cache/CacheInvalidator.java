package com.outpost.core.cache;

import java.util.List;

/**
 * 缓存失效执行器 SPI，具体实现由后续的缓存一致性任务提供。
 */
public interface CacheInvalidator {

    /**
     * 失效指定业务域下的缓存 key。
     */
    void invalidate(String namespace, List<String> keys);
}
