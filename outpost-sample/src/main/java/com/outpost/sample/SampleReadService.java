package com.outpost.sample;

import com.outpost.starter.cache.RockscacheReadClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * tag 模式读路径示例：只有 {@code handler-type=cache_tag_delete} 且
 * {@code tag.read-client=builtin} 时 SDK 才会装配读客户端。
 */
@Service
public class SampleReadService {

    private final ObjectProvider<RockscacheReadClient> readClientProvider;

    public SampleReadService(ObjectProvider<RockscacheReadClient> readClientProvider) {
        this.readClientProvider = readClientProvider;
    }

    public String read(String cacheKey) {
        RockscacheReadClient readClient = readClientProvider.getIfAvailable();
        if (readClient == null) {
            throw new IllegalStateException("当前为 delete 模式（或 read-client=external），"
                    + "未装配 RockscacheReadClient；tag 模式请配置 "
                    + "outpost.delivery-cache.tag.read-client=builtin");
        }
        return readClient.fetch(cacheKey, 60L, () -> "value-from-db:" + cacheKey);
    }
}
