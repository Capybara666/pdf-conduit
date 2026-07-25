package com.pdfconduit.web.dto;

import java.util.List;

/**
 * Server capability flags from {@code GET /api/capabilities}, so the frontend can adapt its UI to
 * what this deployment actually supports (instead of exposing dead inputs/pages) and size its
 * pre-upload guard to what this deployment will actually accept (instead of hard-coding a copy of
 * the limits that silently drifts the moment either side changes).
 *
 * <p>{@code maxFileSizeBytes}/{@code maxFilesPerRequest} are the EFFECTIVE ceilings — the caller's
 * plan narrowed by the deployment-wide guardrails — resolved per request by
 * {@link com.pdfconduit.web.quota.UploadCaps}, the same component
 * {@link com.pdfconduit.web.quota.QuotaInterceptor} enforces them from. {@code maxDpi} comes from
 * the same per-request {@link com.pdfconduit.web.plan.PlanLimits} the render guards in
 * {@code WebOperations} / {@code PipelineLimitsGuard} reject against, resolved through
 * {@link com.pdfconduit.web.plan.RequestPlan}.
 *
 * @param officeEnabled      whether office/document uploads (docx/xlsx/txt/md/html/…) are converted
 *                           ({@code pdfconduit.web.office.enabled}); when {@code false} they are
 *                           rejected with 415
 * @param ocrEnabled         whether {@code /api/ocr} accepts work ({@code pdfconduit.web.ocr.enabled})
 * @param ocrLanguages       installed Tesseract language codes (e.g. {@code eng}, {@code pol}),
 *                           discovered once via {@code tesseract --list-langs}; empty when OCR is
 *                           disabled or the binary is absent
 * @param maxFileSizeBytes   largest single uploaded file this caller may send; anything bigger is
 *                           rejected with 413 {@code too_large}
 * @param maxFilesPerRequest most files this caller may send in one request; more is rejected (413
 *                           on the free-tier ceiling, 400 on the absolute guardrail)
 * @param maxDpi             highest DPI the raster-render endpoints ({@code /api/to-images},
 *                           {@code /api/render}, {@code /api/redact}, and a pipeline's rendering
 *                           nodes) accept; a higher value is rejected with 400 {@code bad_request},
 *                           never silently clamped, so a DPI picker must not offer more than this
 */
public record CapabilitiesInfo(boolean officeEnabled, boolean ocrEnabled, List<String> ocrLanguages,
                               long maxFileSizeBytes, int maxFilesPerRequest, int maxDpi) {}
