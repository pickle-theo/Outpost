package com.outpost.core.cache;

/**
 * 缓存场景内置处理器 bizType 常量。
 */
public final class CacheBizTypes {

    /** 物理删除缓存 key */
    public static final String CACHE_DELETE = "cache_delete";
    /** rockscache 标记逻辑失效 */
    public static final String CACHE_TAG_DELETE = "cache_tag_delete";

    private CacheBizTypes() {
    }
}
