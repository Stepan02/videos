package cloud.videos.services

import com.mongodb.client.gridfs.GridFSBucket
import com.mongodb.client.model.Filters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bson.types.ObjectId
import java.io.ByteArrayInputStream

class DatabaseService(private val gridFSBucket: GridFSBucket) {

    suspend fun saveVideo(data: TranscodedVideoOutput): String = withContext(Dispatchers.IO) {
        // save manifest and file name
        val manifestStream = ByteArrayInputStream(data.playlist)

        val videoId = gridFSBucket.uploadFromStream("manifest.m3u8", manifestStream)
            .toHexString()

        // save thumbnail
        val thumbnailStream = ByteArrayInputStream(data.thumbnail)
        gridFSBucket.uploadFromStream("data/$videoId/thumbnail.jpg", thumbnailStream)

        // save video chunks
        data.chunks.forEach { (chunkName, bytes) ->
            val chunkStream = ByteArrayInputStream(bytes)

            gridFSBucket.uploadFromStream("data/$videoId/chunk/$chunkName", chunkStream)
        }

        return@withContext videoId
    }

    suspend fun getVideoManifest(id: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!ObjectId.isValid(id)) return@withContext null

        val objectId = ObjectId(id)
        val videoManifest = gridFSBucket.find(Filters.eq("_id", objectId)).first()
            ?: return@withContext null

        return@withContext gridFSBucket.openDownloadStream(videoManifest.objectId).readBytes()
    }

    suspend fun getVideoThumbnail(id: String): ByteArray? = withContext(Dispatchers.IO) {
        val videoThumbnail = gridFSBucket.find(Filters.eq("filename", "data/$id/thumbnail.jpg")).first()
            ?: return@withContext null

        return@withContext gridFSBucket.openDownloadStream(videoThumbnail.objectId).readBytes()
    }

    suspend fun getVideoChunk(id: String, name: String): ByteArray? = withContext(Dispatchers.IO) {
        val videoChunk = gridFSBucket.find(Filters.eq("filename", "data/$id/chunk/$name")).first()
            ?: return@withContext null

        return@withContext gridFSBucket.openDownloadStream(videoChunk.objectId).readBytes()
    }
}