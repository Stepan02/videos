package cloud.videos.factories

import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Replaces
import jakarta.inject.Singleton

@Factory
class RedisCodecFactory {

    @Singleton
    @Replaces(RedisCodec::class)
    fun redisCodec(): RedisCodec<String, ByteArray> {
        return RedisCodec.of(StringCodec.UTF8, ByteArrayCodec())
    }
}