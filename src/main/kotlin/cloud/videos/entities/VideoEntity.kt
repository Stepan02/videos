package cloud.videos.entities

import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity

@MappedEntity("videos")
data class VideoEntity(
    @field:Id
    val id: String? = null,
    val title: String,
    val fileId: String,
    val size: Long
)