package cloud.videos.exceptions

class EmptyFileException(
    message: String = "File is empty"
) : RuntimeException(message)

class FileSizeExceededException(
    message: String = "Maximum file size exceeded"
) : RuntimeException(message)

class FileExistsException(
    message: String = "Video already exists"
) : RuntimeException(message)

class MissingNameException(
    message: String = "Name is missing"
) : RuntimeException(message)
