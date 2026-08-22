package cloud.videos.controllers

import cloud.videos.dtos.ErrorResponse
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Error
import io.micronaut.http.exceptions.HttpStatusException
import org.slf4j.LoggerFactory

@Controller
class ErrorController {
    private val logger = LoggerFactory.getLogger(ErrorController::class.java)

    // nonexisting endpoint handler
    @Error(global = true)
    fun handleHttpStatusError(request: HttpRequest<*>, exception: HttpStatusException): HttpResponse<ErrorResponse> {
        if (exception.status == HttpStatus.NOT_FOUND) {
            return HttpResponse.notFound(ErrorResponse("Route '${request.path}' not found"))
        }
        return HttpResponse.status<ErrorResponse>(exception.status)
            .body(ErrorResponse(exception.message ?: "Error occurred"))
    }

    // unhandled internal server error handler
    @Error(exception = Throwable::class, global = true)
    fun handleUnhandledException(request: HttpRequest<*>, exception: Throwable): HttpResponse<ErrorResponse> {
        logger.error("Unhandled exception processing request to ${request.path}", exception)

        return HttpResponse.serverError(ErrorResponse("The server encountered unhandled exception"))
    }
}
