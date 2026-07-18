package com.pdfconduit.web.config;

import com.pdfconduit.web.dto.ApiError;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Documents the {@link ApiError} error body in the OpenAPI spec centrally, so the generated
 * frontend client (R2 WU-B) gets a typed error shape without per-controller {@code @ApiResponse}
 * annotations. Springdoc only emits schemas reachable from a documented response, and
 * {@code ApiError} is produced solely by the {@code @RestControllerAdvice} exception handler, which
 * springdoc does not scan; this customizer bridges that gap.
 *
 * <p>Documentation-only: it neither changes runtime responses nor touches the existing 200s.
 */
@Configuration
public class OpenApiConfig {

    /** Error status codes actually emitted by {@code GlobalExceptionHandler}. */
    private static final String[] ERROR_STATUS_CODES = {"400", "413", "415", "422", "429", "503", "500"};

    private static final String SCHEMA_NAME = "ApiError";
    private static final String SCHEMA_REF = "#/components/schemas/" + SCHEMA_NAME;

    @Bean
    public OpenApiCustomizer apiErrorResponseCustomizer() {
        return openApi -> {
            registerApiErrorSchema(openApi);
            attachErrorResponses(openApi);
        };
    }

    /** Resolve {@link ApiError} to a Schema and register it under {@code components.schemas}. */
    private static void registerApiErrorSchema(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        Components components = openApi.getComponents();
        Map<String, Schema> resolved = ModelConverters.getInstance().read(ApiError.class);
        resolved.forEach(components::addSchemas);
    }

    /** Add an ApiError-bodied response for each real error status to every documented operation. */
    private static void attachErrorResponses(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        Content content = new Content().addMediaType(
            "application/json",
            new MediaType().schema(new Schema<>().$ref(SCHEMA_REF)));

        openApi.getPaths().values().forEach(pathItem ->
            pathItem.readOperations().forEach(operation -> {
                ApiResponses responses = operation.getResponses();
                if (responses == null) {
                    responses = new ApiResponses();
                    operation.setResponses(responses);
                }
                for (String code : ERROR_STATUS_CODES) {
                    if (!responses.containsKey(code)) {
                        responses.addApiResponse(code, new ApiResponse()
                            .description(errorDescription(code))
                            .content(content));
                    }
                }
            }));
    }

    private static String errorDescription(String code) {
        return switch (code) {
            case "400" -> "Bad request";
            case "413" -> "Payload too large";
            case "415" -> "Unsupported media type";
            case "422" -> "Operation failed";
            case "429" -> "Too many requests";
            case "503" -> "Service unavailable";
            case "500" -> "Internal server error";
            default -> "Error";
        };
    }
}
