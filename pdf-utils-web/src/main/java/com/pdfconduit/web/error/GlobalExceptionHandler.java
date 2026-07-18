package com.pdfconduit.web.error;

import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.pipeline.PipelineException;
import com.pdfconduit.web.dto.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Maps exceptions to the status codes and JSON body ({@code {code,error}}) defined in
 * DESIGN.md §3. Client mistakes (bad params, bad page ranges) are 4xx; operation
 * failures the user can understand (wrong password, damaged PDF, LibreOffice missing)
 * are 422; anything unexpected is a generic 500 with the real cause only in the log.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidPageRangeException.class)
    public ResponseEntity<ApiError> onInvalidPageRange(InvalidPageRangeException e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_page_range", e.getMessage());
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        MissingServletRequestParameterException.class,
        MissingServletRequestPartException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiError> onBadRequest(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "bad_request", e.getMessage());
    }

    @ExceptionHandler({PdfOperationException.class, PipelineException.class})
    public ResponseEntity<ApiError> onOperationFailed(Exception e) {
        // The message is already sanitised at the source (core strips LibreOffice stderr / temp
        // paths); log the full cause server-side so operators keep the diagnostics clients don't get.
        log.warn("Operation failed", e);
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "operation_failed", e.getMessage());
    }

    @ExceptionHandler(OfficeDisabledException.class)
    public ResponseEntity<ApiError> onOfficeDisabled(OfficeDisabledException e) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "office_disabled", e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> onTooLarge(MaxUploadSizeExceededException e) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large",
            "The upload exceeds the configured size limit.");
    }

    @ExceptionHandler(TooLargeException.class)
    public ResponseEntity<ApiError> onFreeTierTooLarge(TooLargeException e) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", e.getMessage());
    }

    @ExceptionHandler(ServerBusyException.class)
    public ResponseEntity<ApiError> onServerBusy(ServerBusyException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "server_busy", e.getMessage());
    }

    @ExceptionHandler(ProcessingTimeoutException.class)
    public ResponseEntity<ApiError> onProcessingTimeout(ProcessingTimeoutException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "processing_timeout", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> onUnexpected(Exception e) {
        log.error("Unexpected error handling request", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred.");
    }

    private static ResponseEntity<ApiError> body(HttpStatus status, String code, String message) {
        String msg = (message == null || message.isBlank()) ? status.getReasonPhrase() : message;
        return ResponseEntity.status(status).body(new ApiError(code, msg));
    }
}
