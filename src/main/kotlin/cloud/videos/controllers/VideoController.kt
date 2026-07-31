package cloud.videos.controllers

import cloud.videos.dtos.ErrorResponse
import cloud.videos.dtos.UploadResponse
import cloud.videos.exceptions.EmptyFileException
import cloud.videos.exceptions.FileSizeExceededException
import cloud.videos.services.CacheService
import cloud.videos.services.DatabaseService
import cloud.videos.services.VideoService
import com.mongodb.client.gridfs.GridFSBucket
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.multipart.CompletedFileUpload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

@Controller("/videos")
class VideoController(gridFSBucket: GridFSBucket, private val cacheService: CacheService, private val videoService: VideoService) {

    private val logger = LoggerFactory.getLogger(VideoController::class.java)
    private val databaseService = DatabaseService(gridFSBucket)

    @Post("/upload", consumes = [MediaType.MULTIPART_FORM_DATA])
    suspend fun uploadVideo(file: CompletedFileUpload): HttpResponse<Any> {
        try {
            val maxFileSize: Long = 500 * 1024 * 1024 // 500 MB upload file limit

            if (file.size <= 0) {
                throw EmptyFileException("File is empty")
            }

            if (file.size > maxFileSize) {
                throw FileSizeExceededException("Maximum file size exceeded")
            }

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
                databaseService.saveVideo(transcodedVideo)
            }

            logger.info("Caching video...")

            // upload video to cache
            withContext(Dispatchers.IO) {
                cacheService.saveVideo(videoId, transcodedVideo)
            }

            logger.info("Video {} cached", videoId)

            return HttpResponse.created(
                UploadResponse(
                    id = videoId,
                    filename = file.filename,
                    size = file.size.toString(),
                )
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IllegalStateException) {
            return HttpResponse.serverError(ErrorResponse(exception.message.toString()))
        } catch (exception: EmptyFileException) {
            return HttpResponse.badRequest(ErrorResponse(exception.message.toString()))
        } catch (exception: FileSizeExceededException) {
            return HttpResponse.badRequest(ErrorResponse(exception.message.toString()))
        }
    }

    @Get("/{id}/thumbnail")
    suspend fun getVideoThumbnail(id: String): HttpResponse<out Any> {
        try {
            // cache
            val thumbnailBytesCached = cacheService.getVideoThumbnail(id)

            // cache hit
            if (thumbnailBytesCached !== null) {
                return HttpResponse.ok(thumbnailBytesCached)
                    .contentType(MediaType.IMAGE_JPEG)
            }

            // cache miss
            val thumbnailBytes = databaseService.getVideoThumbnail(id)
                ?: return HttpResponse.notFound(ErrorResponse("Thumbnail not found"))

            return HttpResponse.ok(thumbnailBytes)
                .contentType(MediaType.IMAGE_JPEG)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error(exception.message, exception)

            return HttpResponse.serverError(ErrorResponse("Failed to get video thumbnail"))
        }
    }

    @Get("/{id}/manifest.m3u8")
    suspend fun getVideoManifest(id: String): HttpResponse<out Any> {
        try {
            // cache
            val manifestContentCached = cacheService.getVideoManifest(id)

            // cache hit
            if (manifestContentCached !== null) {
                return HttpResponse.ok(manifestContentCached)
                    .contentType(MediaType.of("application/x-mpegurl"))
            }

            // cache miss
            val manifestContent = databaseService.getVideoManifest(id)
                ?: return HttpResponse.notFound(ErrorResponse("Manifest not found"))

            return HttpResponse.ok(manifestContent)
                .contentType(MediaType.of("application/x-mpegurl"))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error(exception.message, exception)

            return HttpResponse.serverError(ErrorResponse("Failed to get video manifest"))
        }
    }

    @Get("/{id}/{chunkName}")
    suspend fun getVideoChunk(id: String, chunkName: String): HttpResponse<out Any> {
        try {
            // cache
            val chunkBytesCached = cacheService.getVideoChunk(id, chunkName)

            // cache hit
            if (chunkBytesCached !== null) {
                return HttpResponse.ok(chunkBytesCached)
                    .contentType(MediaType.of("video/mp2t"))
            }

            // cache miss
            val chunkContent = databaseService.getVideoChunk(id, chunkName)
                ?: return HttpResponse.notFound(ErrorResponse("Video chunk not found"))

            return HttpResponse.ok(chunkContent)
                .contentType(MediaType.of("video/mp2t"))
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
            // delete from cache
            cacheService.deleteVideo(id)

            logger.info("Video $id deleted from cache")

            // delete from database
            val videoDeletedFromDatabase = databaseService.deleteVideo(id)

            // deleteVideo returns false if the video id has invalid format
            if (!videoDeletedFromDatabase) {
                return HttpResponse.badRequest(ErrorResponse("Invalid video ID"))
            }

            logger.info("Video $id deleted from database")

            return HttpResponse.noContent()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error(exception.message, exception)

            return HttpResponse.serverError(ErrorResponse("Failed to delete video"))
        }
    }
}