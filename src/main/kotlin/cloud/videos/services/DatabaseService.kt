package cloud.videos.services

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.gridfs.GridFSBucket
import com.mongodb.client.gridfs.model.GridFSUploadOptions
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Filters.*
import com.mongodb.client.model.Sorts.descending
import io.micronaut.serde.annotation.Serdeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bson.Document
import org.bson.types.ObjectId
import java.io.ByteArrayInputStream

@Serdeable
data class VideoMetadata(
    val id: String,
    val name: String,
    val size: Long,
    val uploadDate: String,
)

class DatabaseService(private val gridFSBucket: GridFSBucket, private val mongoClient: MongoClient) {

    fun connectDatabase(): MongoCollection<Document?> = mongoClient
        .getDatabase("videos")
        .getCollection("videos.files")

    suspend fun saveVideo(data: TranscodedVideoOutput, name: String): String = withContext(Dispatchers.IO) {
        // save manifest and file name
        val manifestStream = ByteArrayInputStream(data.playlist)

        // add video name to the object
        val videoMetadata = Document().apply {
            append("name", name)
        }
        val options = GridFSUploadOptions().metadata(videoMetadata)

        val videoId = gridFSBucket.uploadFromStream("manifest.m3u8", manifestStream, options)
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

    suspend fun getVideosMetadataList(limit: Int = 10, lastVideoId: String?): List<VideoMetadata> = withContext(Dispatchers.IO) {
        if (lastVideoId != null && !ObjectId.isValid(lastVideoId)) {
            return@withContext emptyList()
        }

        // exclude manifest
        val filter = eq("filename", "manifest.m3u8")

        // include older videos
        val lastVideoFilter = if (lastVideoId != null) {
            and(lt("_id", ObjectId(lastVideoId)), filter)
        } else {
            // filter is blank if no lastVideoId is provided - return newest ones
            filter
        }

        return@withContext connectDatabase()
            .find(lastVideoFilter)
            .sort(descending("_id"))
            .limit(limit)
            .toList()
            .filterNotNull()
            .map { videoDocument ->
                VideoMetadata(
                    id = videoDocument.getObjectId("_id").toHexString(),
                    name = videoDocument.get("metadata", Document::class.java)
                        ?.getString("name") ?: "unknown",
                    size = videoDocument.getLong("length") ?: 0L,
                    uploadDate = videoDocument.getDate("uploadDate")?.toInstant()?.toString() ?: "unknown"
                )
            }
    }

    fun getVideoMetadata(id: String): VideoMetadata? {
        if (!ObjectId.isValid(id)) return null

        val objectId = ObjectId(id)
        val videoMetadata = connectDatabase()
            .find(eq("_id", objectId))
            .firstOrNull() ?: return null

        return VideoMetadata(
            id = id,
            name = videoMetadata.get("metadata", Document::class.java)
                ?.getString("name") ?: "unknown",
            size = videoMetadata.getLong("length") ?: 0L,
            uploadDate = videoMetadata.getDate("uploadDate")?.toInstant()?.toString() ?: "unknown"
        )
    }

    suspend fun getVideoManifest(id: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!ObjectId.isValid(id)) return@withContext null

        val objectId = ObjectId(id)
        val videoManifest = gridFSBucket.find(eq("_id", objectId)).first()
            ?: return@withContext null

        return@withContext gridFSBucket.openDownloadStream(videoManifest.objectId).readBytes()
    }

    suspend fun getVideoThumbnail(id: String): ByteArray? = withContext(Dispatchers.IO) {
        val videoThumbnail = gridFSBucket.find(eq("filename", "data/$id/thumbnail.jpg")).first()
            ?: return@withContext null

        return@withContext gridFSBucket.openDownloadStream(videoThumbnail.objectId).readBytes()
    }

    suspend fun getVideoChunk(id: String, name: String): ByteArray? = withContext(Dispatchers.IO) {
        val videoChunk = gridFSBucket.find(eq("filename", "data/$id/chunk/$name")).first()
            ?: return@withContext null

        return@withContext gridFSBucket.openDownloadStream(videoChunk.objectId).readBytes()
    }

    suspend fun deleteVideo(id: String): Boolean = withContext(Dispatchers.IO) {
        // return false if the object id is invalid
        if (!ObjectId.isValid(id)) return@withContext false

        val objectId = ObjectId(id)

        // check whether video manifest exists - return success if it does not exist
        gridFSBucket.find(eq("_id", objectId)).first() ?: return@withContext true

        // delete thumbnail and video chunks
        val videoFiles = gridFSBucket.find(Filters.regex("filename", "^data/$id/")).toList()

        videoFiles.forEach { file ->
            gridFSBucket.delete(file.objectId)
        }

        // delete manifest
        gridFSBucket.delete(ObjectId(id))

        // return true indicating the deletion succeeded
        return@withContext true
    }
}
