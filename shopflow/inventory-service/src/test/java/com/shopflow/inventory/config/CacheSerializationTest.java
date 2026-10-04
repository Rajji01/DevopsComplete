package com.shopflow.inventory.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

import com.shopflow.inventory.product.ProductResponse;

/** Guards against the classic "works with simple cache, explodes on first Redis put" bug. */
class CacheSerializationTest {

    @Test
    void productResponseSurvivesTheRedisJsonRoundTrip() {
        var serializer = new GenericJackson2JsonRedisSerializer();
        var original = new ProductResponse("PS5-SLIM", "PlayStation 5 Slim", 3);

        byte[] bytes = serializer.serialize(original);
        Object restored = serializer.deserialize(bytes);

        assertThat(new String(bytes)).contains("\"sku\":\"PS5-SLIM\"");
        assertThat(restored).isEqualTo(original);
    }
}
