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
import org.bson.BsonObjectId
import org.bson.Document
import org.bson.types.ObjectId
import java.io.ByteArrayInputStream
import java.util.regex.Pattern

@Serdeable
data class VideoMetadata(
    val id: String,
    val name: String,
    val totalSize: Long,
    val uploadDate: String,
    val duration: Double,
    val width: Int,
    val height: Int,
)

class DatabaseService(private val gridFSBucket: GridFSBucket, private val mongoClient: MongoClient) {

    fun connectDatabase(): MongoCollection<Document?> = mongoClient
        .getDatabase("videos")
        .getCollection("videos.files")

    suspend fun saveVideo(videoId: ObjectId, data: TranscodedVideoOutput, name: String): String =
        withContext(Dispatchers.IO) {
            val objectId = BsonObjectId(videoId)
            val videoObjectId = videoId.toHexString()

            // save manifest and file name
            val manifestStream = ByteArrayInputStream(data.playlist)

            // calculate video size
            val videoSize = data.playlist.size.toLong() +
                data.thumbnail.size.toLong() +
                data.chunks.values.sumOf { it.size.toLong() }

            // add video name, size, duration, width and height to the object
            val videoMetadata = Document().apply {
                append("name", name)
                append("totalSize", videoSize)
                append("duration", data.duration)
                append("width", data.width)
                append("height", data.height)
            }
            val options = GridFSUploadOptions().metadata(videoMetadata)

            gridFSBucket.uploadFromStream(objectId, "manifest.m3u8", manifestStream, options)

            // save thumbnail
            val thumbnailStream = ByteArrayInputStream(data.thumbnail)
            gridFSBucket.uploadFromStream("data/$videoObjectId/thumbnail.jpg", thumbnailStream)

            // save video chunks
            data.chunks.forEach { (chunkName, bytes) ->
                val chunkStream = ByteArrayInputStream(bytes)

                gridFSBucket.uploadFromStream("data/$videoObjectId/chunk/$chunkName", chunkStream)
            }

            return@withContext videoObjectId
        }

    suspend fun getVideosMetadataList(limit: Int = 10, lastVideoId: String?): List<VideoMetadata> = withContext(Dispatchers.IO) {
        if (lastVideoId != null && !ObjectId.isValid(lastVideoId)) return@withContext emptyList()

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
                    totalSize = videoDocument.get("metadata", Document::class.java)
                        ?.getLong("totalSize") ?: 0L,
                    uploadDate = videoDocument.getDate("uploadDate")?.toInstant()?.toString() ?: "unknown",
                    duration = videoDocument.get("metadata", Document::class.java)
                        .getDouble("duration"),
                    width = videoDocument.get("metadata", Document::class.java)
                        .getInteger("width"),
                    height = videoDocument.get("metadata", Document::class.java)
                        .getInteger("height"),
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
            totalSize = videoMetadata.get("metadata", Document::class.java)
                ?.getLong("totalSize") ?: 0L,
            uploadDate = videoMetadata.getDate("uploadDate")?.toInstant()?.toString() ?: "unknown",
            duration = videoMetadata.get("metadata", Document::class.java)
                .getDouble("duration"),
            width = videoMetadata.get("metadata", Document::class.java)
                .getInteger("width"),
            height = videoMetadata.get("metadata", Document::class.java)
                .getInteger("height"),
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

    suspend fun searchVideoByName(name: String, limit: Int, lastVideoId: String?): List<VideoMetadata> = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext emptyList()
        if (lastVideoId != null && !ObjectId.isValid(lastVideoId)) return@withContext emptyList()

        val fileFilter = and(
            eq("filename", "manifest.m3u8"),
            regex("metadata.name", Pattern.quote(name), "i")
        )

        // include older videos
        val lastVideoFilter = if (lastVideoId != null) {
            and(
                fileFilter,
                lt("_id", ObjectId(lastVideoId))
            )
        } else {
            // filter is blank if no lastVideoId is provided - return newest ones
            fileFilter
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
                    totalSize = videoDocument.get("metadata", Document::class.java)
                        ?.getLong("totalSize") ?: 0L,
                    uploadDate = videoDocument.getDate("uploadDate")?.toInstant()?.toString() ?: "unknown",
                    duration = videoDocument.get("metadata", Document::class.java)
                        .getDouble("duration"),
                    width = videoDocument.get("metadata", Document::class.java)
                        .getInteger("width"),
                    height = videoDocument.get("metadata", Document::class.java)
                        .getInteger("height"),
                )
            }
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
