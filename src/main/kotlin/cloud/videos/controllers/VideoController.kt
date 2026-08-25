package cloud.videos.controllers

import cloud.videos.dtos.ErrorResponse
import cloud.videos.dtos.UploadResponse
import cloud.videos.dtos.VideoBulkDeleteResponse
import cloud.videos.exceptions.EmptyFileException
import cloud.videos.exceptions.FileSizeExceededException
import cloud.videos.exceptions.InvalidFileFormatException
import cloud.videos.exceptions.MissingNameException
import cloud.videos.services.CacheService
import cloud.videos.services.DatabaseService
import cloud.videos.services.VideoService
import com.mongodb.client.MongoClient
import com.mongodb.client.gridfs.GridFSBucket
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.http.multipart.CompletedFileUpload
import kotlinx.coroutines.*
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory

@Controller("/videos")
class VideoController(
    gridFSBucket: GridFSBucket,
    mongoClient: MongoClient,
    private val cacheService: CacheService,
    private val videoService: VideoService
) {

    private val logger = LoggerFactory.getLogger(VideoController::class.java)
    private val databaseService = DatabaseService(gridFSBucket, mongoClient)

    @Get("/")
    suspend fun getVideosMetadataList(
        @QueryValue limit: String?,
        @QueryValue lastVideoId: String? = null,
        @QueryValue search: String? = null
    ): HttpResponse<out Any> {
        try {
            val recordsLimit = if (limit.isNullOrBlank()) 10 else limit.toIntOrNull()
                ?: return HttpResponse.badRequest(ErrorResponse("Limit must be a valid number"))

            if (recordsLimit < 1) {
                return HttpResponse.badRequest(ErrorResponse("Limit must be greater than 0"))
            }

            val videosList = if (!search.isNullOrBlank()) {
                // search for the video if the search parameter is present
                databaseService.searchVideoByName(search, recordsLimit, lastVideoId)
            } else {
                // get video list
                databaseService.getVideosMetadataList(recordsLimit, lastVideoId)
            }

            return HttpResponse.ok(videosList)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error(exception.message, exception)

            return HttpResponse.serverError(ErrorResponse("Failed to get videos list"))
        }
    }

    @Post("/upload", consumes = [MediaType.MULTIPART_FORM_DATA])
    suspend fun uploadVideo(@Part file: CompletedFileUpload, @Part name: String): HttpResponse<Any> {
        try {
            // https://www.ffmpeg.org/general.html#Supported-File-Formats_002c-Codecs-or-Features
            val allowedFileFormats = setOf("mp4", "m4v", "mov", "webm", "mkv", "avi", "flv", "wmv", "3gp")
            val uploadedFileFormat = file.filename.substringAfterLast(".", "").lowercase()

            if (uploadedFileFormat !in allowedFileFormats) {
                throw InvalidFileFormatException("Invalid file format")
            }

            val maxFileSize: Long = 500 * 1024 * 1024 // 500 MB upload file limit

            if (file.size <= 0) {
                throw EmptyFileException("File is empty")
            }

            if (file.size > maxFileSize) {
                throw FileSizeExceededException("Maximum file size exceeded")
            }

            if (name.isBlank()) {
                throw MissingNameException("Video name is required")
            }

            // generate video id
            val videoObjectId = ObjectId()
            val videoId = videoObjectId.toHexString()

            // add video to processing queue
            cacheService.addToProcessingQueue(videoId)

            // start processing coroutine
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    logger.info("Accepted file {} (size: {} bytes)", file.filename, file.size)

                    // measure ffmpeg post-processing time
                    val processingStartTime = System.currentTimeMillis()
                    logger.info("Starting video processing...")

                    // transcode the video
                    val transcodedVideo = withContext(Dispatchers.IO) {
                        file.inputStream.use { stream ->
                            videoService.processVideo(stream)
                        }
                    }

                    val processingDuration = System.currentTimeMillis() - processingStartTime
                    logger.info("Video processing finished in {} ms", processingDuration)

                    // upload video to database
                    val videoId = withContext(Dispatchers.IO) {
                        databaseService.saveVideo(videoObjectId, transcodedVideo, name)
                    }

                    logger.info("Caching video...")

                    // upload video to cache
                    withContext(Dispatchers.IO) {
                        cacheService.saveVideo(videoId, transcodedVideo)
                    }

                    logger.info("Video {} cached", videoId)

                    // remove video from processing queue
                    cacheService.removeFromProcessingQueue(videoId)
                } catch (exception: Exception) {
                    // remove video from processing queue
                    cacheService.removeFromProcessingQueue(videoId)

                    logger.error("Failed to process video {}: {}", videoId, exception.message)
                }
            }

            return HttpResponse.created(
                UploadResponse(
                    id = videoId,
                    name = name,
                    filename = file.filename,
                    size = file.size.toString(),
                )
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: MissingNameException) {
            return HttpResponse.badRequest(ErrorResponse(exception.message.toString()))
        } catch (exception: IllegalStateException) {
            return HttpResponse.serverError(ErrorResponse(exception.message.toString()))
        } catch (exception: EmptyFileException) {
            return HttpResponse.badRequest(ErrorResponse(exception.message.toString()))
        } catch (exception: InvalidFileFormatException) {
            return HttpResponse.badRequest(ErrorResponse(exception.message.toString()))
        } catch (exception: FileSizeExceededException) {
            return HttpResponse.status<String>(HttpStatus.REQUEST_ENTITY_TOO_LARGE)
                .body(ErrorResponse(exception.message.toString()))
        }
    }

    @Get("/{id}/thumbnail")
    suspend fun getVideoThumbnail(id: String): HttpResponse<out Any> {
        try {
            // check processing queue
            if (cacheService.isInProcessingQueue(id)) {
                return HttpResponse.status<Any>(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorResponse("Video is currently being processed, try again later"))
            }

            // cache
            val thumbnailBytesCached = cacheService.getVideoThumbnail(id)

            // cache hit
            if (thumbnailBytesCached !== null) {
                return HttpResponse.ok(thumbnailBytesCached)
                    .contentType(MediaType.IMAGE_JPEG)
            }

            // cache miss
            val thumbnailBytes = databaseService.getVideoThumbnail(id)
                ?: return HttpResponse.notFound(ErrorResponse("Thumbnail does not exist or is currently being processed, try again later"))

            return HttpResponse.ok(thumbnailBytes)
                .contentType(MediaType.IMAGE_JPEG)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error(exception.message, exception)

            return HttpResponse.serverError(ErrorResponse("Failed to get video thumbnail"))
        }
    }

    @Get("/{id}")
    suspend fun getVideoMetadata(id: String): HttpResponse<Any> {
        try {
            // check processing queue
            if (cacheService.isInProcessingQueue(id)) {
                return HttpResponse.status<Any>(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorResponse("Video is currently being processed, try again later"))
            }

            val videoMetadata = databaseService.getVideoMetadata(id)
                ?: return HttpResponse.notFound(ErrorResponse("Video does not exist or is currently being processed, try again later"))

            return HttpResponse.ok(videoMetadata)
        } catch (exception: Exception) {
            logger.error(exception.message, exception)

            return HttpResponse.serverError(ErrorResponse("Failed to get video metadata"))
        }
    }

    @Get("/{id}/manifest.m3u8")
    suspend fun getVideoManifest(id: String): HttpResponse<out Any> {
        try {
            // check processing queue
            if (cacheService.isInProcessingQueue(id)) {
                return HttpResponse.status<Any>(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorResponse("Video is currently being processed, try again later"))
            }

            // cache
            val manifestContentCached = cacheService.getVideoManifest(id)

            // cache hit
            if (manifestContentCached !== null) {
                return HttpResponse.ok(manifestContentCached)
                    .contentType(MediaType.of("application/x-mpegurl"))
            }

            // cache miss
            val manifestContent = databaseService.getVideoManifest(id)
                ?: return HttpResponse.notFound(ErrorResponse("Video does not exist or is currently being processed, try again later"))

            return HttpResponse.ok(manifestContent)
                .contentType(MediaType.of("application/x-mpegurl"))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error(exception.message, exception)

            return HttpResponse.serverError(ErrorResponse("Failed to get video manifest"))
        }
    }

    @Get("/{id}/{chunkName}.ts")
    suspend fun getVideoChunk(id: String, chunkName: String): HttpResponse<out Any> {
        try {
            // check processing queue
            if (cacheService.isInProcessingQueue(id)) {
                return HttpResponse.status<Any>(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorResponse("Video is currently being processed, try again later"))
            }

            val chunkFile = "$chunkName.ts"

            // cache
            val chunkBytesCached = cacheService.getVideoChunk(id, chunkFile)

            // cache hit
            if (chunkBytesCached != null) {
                return HttpResponse.ok(chunkBytesCached)
                    .contentType(MediaType.of("video/mp2t"))
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
            }

            // cache miss
            val chunkContent = databaseService.getVideoChunk(id, chunkFile)
                ?: return HttpResponse.notFound(ErrorResponse("Chunk does not exist or is currently being processed, try again later"))

            // cache the chunk
            cacheService.saveVideoChunk(id, chunkFile, chunkContent)

            return HttpResponse.ok(chunkContent)
                .contentType(MediaType.of("video/mp2t"))
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error(exception.message, exception)

            return HttpResponse.serverError(ErrorResponse("Failed to get video chunk"))
        }
    }

    @Delete("/{id}")
    suspend fun deleteVideo(id: String): HttpResponse<out Any> {
        try {
            // check processing queue
            if (cacheService.isInProcessingQueue(id)) {
                return HttpResponse.status<Any>(HttpStatus.CONFLICT)
                    .body(ErrorResponse("Video is currently being processed, try again later"))
            }

            // delete from database
            val videoDeletedFromDatabase = databaseService.deleteVideo(id)

            // deleteVideo returns false if the video id has invalid format
            if (!videoDeletedFromDatabase) {
                return HttpResponse.badRequest(ErrorResponse("Invalid video ID"))
            }

            logger.info("Video {} deleted from database", id)

            // delete from cache
            cacheService.deleteVideo(id)

            logger.info("Video {} deleted from cache", id)

            return HttpResponse.noContent()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error(exception.message, exception)

            return HttpResponse.serverError(ErrorResponse("Failed to delete video"))
        }
    }

    @Delete("/bulk")
    suspend fun deleteVideos(@Body videos: List<String>): HttpResponse<out Any> {
        try {
            logger.info("Deleting {} videos", videos.size)

            val failedDeletionsList = coroutineScope {
                videos.map { id ->
                    async {
                        // skip videos currently being processed to avoid conflict
                        if (cacheService.isInProcessingQueue(id)) {
                            logger.warn("Failed to delete video {} because it is currently being processed", id)
                            return@async id
                        }

                        // delete from database
                        val videoDeletedFromDatabase = databaseService.deleteVideo(id)

                        // deleteVideo returns false if the video id has invalid format
                        if (!videoDeletedFromDatabase) {
                            logger.error("Failed to delete video {}", id)

                            id // return id of the video in case of a failure
                        } else {
                            // delete from cache
                            cacheService.deleteVideo(id)

                            logger.info("Video {} deleted from cache", id)

                            null // return null if the video was deleted successfully
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            val failedCount = failedDeletionsList.size
            val deletedCount = videos.size - failedCount

            return HttpResponse.ok(
                VideoBulkDeleteResponse(
                    deletedCount = deletedCount,
                    failedCount = failedCount,
                    failedDeletions = failedDeletionsList
                )
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error(exception.message, exception)

            return HttpResponse.serverError(ErrorResponse("Failed to delete videos"))
        }
    }
}
