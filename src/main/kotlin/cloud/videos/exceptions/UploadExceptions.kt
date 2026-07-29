package cloud.videos.exceptions

class EmptyFileException(message: String) : RuntimeException(message)
class FileSizeExceededException(message: String) : RuntimeException(message)
class FileExistsException(message: String) : RuntimeException(message)

class MissingManifestException(message: String) : RuntimeException(message)
class MissingThumbnailException(message: String) : RuntimeException(message)
class MissingChunkException(message: String) : RuntimeException(message)