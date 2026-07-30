package cloud.videos.exceptions

class EmptyFileException(message: String) : RuntimeException(message)
class FileSizeExceededException(message: String) : RuntimeException(message)
class FileExistsException(message: String) : RuntimeException(message)