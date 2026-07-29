package cloud.videos.services

import cloud.videos.exceptions.FileExistsException
import cloud.videos.exceptions.MissingChunkException
import cloud.videos.exceptions.MissingManifestException
import cloud.videos.exceptions.MissingThumbnailException
import io.lettuce.core.api.StatefulRedisConnection
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext

@Singleton
class CacheService(private val connection: StatefulRedisConnection<String, ByteArray>) {

    suspend fun saveVideo(id: String, data: TranscodedVideoOutput, ttlSeconds: Long = 3600): Unit = withContext(Dispatchers.IO) {
        val commands = connection.sync()

        // lookup the video for duplicates
        if (commands.exists("video:$id:manifest") > 0) {
            throw FileExistsException("Video already exists")
        }

        commands.multi()

        // save manifest
        commands.setex("video:$id:manifest", ttlSeconds, data.playlist)

        // save thumbnail image
        commands.setex("video:$id:thumbnail", ttlSeconds,  data.thumbnail)

        // save video chunks
        data.chunks.forEach { (chunkName, bytes) ->
            commands.setex("video:$id:chunk:$chunkName", ttlSeconds, bytes)
        }

        commands.exec()
    }

    suspend fun getVideoManifest(id: String): ByteArray {
        val commands = connection.async()

        val manifest = commands.get("video:$id:manifest").await()
            ?: throw MissingManifestException("Video manifest not found")

        return manifest
    }

    suspend fun getVideoThumbnail(id: String): ByteArray {
        val commands = connection.async()

        val thumbnail = commands.get("video:$id:thumbnail").await()
            ?: throw MissingThumbnailException("Video thumbnail not found")

        return thumbnail
    }

    suspend fun getVideoChunk(id: String, name: String): ByteArray {
        val commands = connection.async()

        val chunk = commands.get("video:$id:chunk:$name").await()
            ?: throw MissingChunkException("Video $id chunk not found")

        return chunk
    }
}