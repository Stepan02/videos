package cloud.videos.services

import cloud.videos.exceptions.FileExistsException
import io.lettuce.core.api.StatefulRedisConnection
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
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
        commands.setex("video:$id:thumbnail", ttlSeconds, data.thumbnail)

        // save video chunks
        data.chunks.forEach { (chunkName, bytes) ->
            commands.setex("video:$id:chunk:$chunkName", ttlSeconds, bytes)
        }

        commands.exec()
    }

    suspend fun getVideoManifest(id: String): ByteArray? {
        val commands = connection.async()

        val manifest = commands.get("video:$id:manifest").await()
            ?: return null

        return manifest
    }

    suspend fun getVideoThumbnail(id: String): ByteArray? {
        val commands = connection.async()

        val thumbnail = commands.get("video:$id:thumbnail").await()
            ?: return null

        return thumbnail
    }

    suspend fun getVideoChunk(id: String, name: String): ByteArray? {
        val commands = connection.async()

        val chunk = commands.get("video:$id:chunk:$name").await()
            ?: return null

        return chunk
    }

    suspend fun deleteVideo(id: String) {
        val commands = connection.reactive()

        // the video manifest contains list of video chunks - use that to get keys to delete list
        val manifestBytes = commands.get("video:$id:manifest").awaitFirstOrNull()
            ?: return

        // get manifest bytes
        val manifest = manifestBytes.decodeToString()

        // parse chunk list from the manifest
        // every line of the manifest which doesn't start with "#" contains a chunk name
        // (reference: https://datatracker.ietf.org/doc/html/rfc8216#section-4.1)
        val chunks = manifest.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

        // add every key associated with the video (manifest, thumbnail and chunks) to one list
        val keysToDelete = ArrayList<String>(chunks.size + 2).apply {
            add("video:$id:manifest")
            add("video:$id:thumbnail")

            chunks.forEach {
                add("video:$id:chunk:$it")
            }
        }

        // bulk delete the whole key list
        commands.del(*keysToDelete.toTypedArray()).awaitSingle()
    }
}