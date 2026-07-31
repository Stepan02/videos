package cloud.videos.services

import jakarta.inject.Singleton
import org.bytedeco.ffmpeg.ffmpeg
import org.bytedeco.javacpp.Loader
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.*

data class TranscodedVideoOutput(
    val playlist: ByteArray,
    val chunks: Map<String, ByteArray>,
    val thumbnail: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TranscodedVideoOutput

        if (!playlist.contentEquals(other.playlist)) return false
        if (chunks != other.chunks) return false
        if (!thumbnail.contentEquals(other.thumbnail)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = playlist.contentHashCode()
        result = 31 * result + chunks.hashCode()
        result = 31 * result + thumbnail.contentHashCode()
        return result
    }
}

@Singleton
class VideoService {

    private val ffmpegPath: String by lazy {
        Loader.load(ffmpeg::class.java)
    }
    private val logger = LoggerFactory.getLogger(VideoService::class.java)

    fun processVideo(inputStream: InputStream): TranscodedVideoOutput {
        val requestId = UUID.randomUUID().toString()

        val videoFile = File.createTempFile("temp-$requestId", ".mp4")
        val outputDirectory = Files.createTempDirectory("video-out-$requestId").toFile()

        val ffmpegVideoLogs = File.createTempFile("ffmpeg-video-log-$requestId", ".log")
        val ffmpegThumbnailLogs = File.createTempFile("ffmpeg-thumbnail-log-$requestId", ".log")

        try {
            // write stream to temporary file
            videoFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }

            // transcode to hls with ffmpeg
            val playlistFile = File(outputDirectory, "index.m3u8")

            val transcodingProcess = ProcessBuilder(
                ffmpegPath, "-y",
                "-i", videoFile.absolutePath,
                "-f", "hls",
                "-hls_time", "3",
                "-hls_playlist_type", "vod",
                playlistFile.absolutePath,
            ).redirectErrorStream(true).redirectOutput(ffmpegVideoLogs).start()

            val transcodingExitCode = transcodingProcess.waitFor()

            if (transcodingExitCode != 0) {
                val errorLog = ffmpegVideoLogs.readText()
                logger.error("Video processing failed with code {}: {}", transcodingExitCode, errorLog)
                throw IllegalStateException("Video processing failed")
            }

            // create video thumbnail on 2nd second of the video
            val videoThumbnailFile = File(outputDirectory, "thumbnail-$requestId.jpg")
            val thumbnailProcess = ProcessBuilder(
                ffmpegPath, "-y",
                "-ss", "00:00:02",
                "-i", videoFile.absolutePath,
                "-vframes", "1",
                videoThumbnailFile.absolutePath,
            ).redirectErrorStream(true).redirectOutput(ffmpegThumbnailLogs).start()

            val thumbnailExitCode = thumbnailProcess.waitFor()

            if (thumbnailExitCode != 0) {
                val errorLog = ffmpegThumbnailLogs.readText()
                logger.error("Thumbnail processing failed with code {}: {}", thumbnailExitCode, errorLog)
                throw IllegalStateException("Thumbnail processing failed")
            }


            // load the video files to ram
            val playlistBytes = playlistFile.readBytes()
            val thumbnailBytes = videoThumbnailFile.readBytes()

            val chunksMap = mutableMapOf<String, ByteArray>()
            outputDirectory.listFiles { _, name -> name.endsWith(".ts") }?.forEach { chunkFile ->
                chunksMap[chunkFile.name] = chunkFile.readBytes()
            }

            return TranscodedVideoOutput(
                playlist = playlistBytes,
                chunks = chunksMap,
                thumbnail = thumbnailBytes
            )
        } finally {
            // delete temporary files and directory
            runCatching { videoFile.delete() }
            runCatching { ffmpegVideoLogs.delete() }
            runCatching { ffmpegThumbnailLogs.delete() }
            runCatching { outputDirectory.deleteRecursively() }
        }
    }
}