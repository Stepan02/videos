package cloud.videos.dtos

import io.micronaut.serde.annotation.Serdeable

@Serdeable
data class UploadResponse(
    val id: String,
    val name: String,
    val filename: String,
    val size: String
)

@Serdeable
data class ErrorResponse(
    val error: String,
)

@Serdeable
data class VideoResponse(
    val id: String,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VideoResponse

        if (id != other.id) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

@Serdeable
data class VideoBulkDeleteResponse(
    val deletedCount: Int,
    val failedCount: Int,
    val failedDeletions: List<String>
)
