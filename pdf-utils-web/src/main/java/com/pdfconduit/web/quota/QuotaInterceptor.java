package com.pdfconduit.web.quota;

import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.error.TooLargeException;
import com.pdfconduit.web.support.ClientIp;
import com.pdfconduit.web.support.Endpoints;
import com.pdfconduit.web.support.JsonErrors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Map;

/**
 * The free-tier gate for operation-producing endpoints. Runs after the rate-limit filter and
 * before the controller. It:
 * <ul>
 *   <li>enforces the absolute per-request file-count cap on every operation endpoint (including
 *       the single-file ones and {@code /pipeline/run}, which the controllers' own guardCount
 *       skips) → 400;</li>
 *   <li>enforces the free-tier per-file size cap and per-request file cap → 413 {@code too_large};</li>
 *   <li>rejects requests once the caller's daily free operation count is spent → 429
 *       {@code quota_exceeded}, and emits {@code X-Quota-*} headers;</li>
 *   <li>counts a successful operation (2xx) in {@code afterCompletion} so failures don't burn quota.</li>
 * </ul>
 * The free-tier checks and counting are skipped entirely when {@code quota.enabled=false}; the hard
 * file-count cap always applies.
 */
@Component
public class QuotaInterceptor implements HandlerInterceptor {

    private final QuotaService quota;
    private final boolean quotaEnabled;
    private final int freeMaxFiles;
    private final long freeMaxFileBytes;
    private final int hardMaxFiles;

    public QuotaInterceptor(QuotaService quota, WebProperties props) {
        this.quota = quota;
        this.quotaEnabled = props.quota().enabled();
        this.freeMaxFiles = props.quota().freeMaxFiles();
        DataSize freeSize = props.quota().freeMaxFileSize();
        this.freeMaxFileBytes = freeSize.toBytes();
        this.hardMaxFiles = props.maxFilesPerRequest();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = Endpoints.path(request);
        if (!Endpoints.isQuotaOp(path)) return true;

        List<MultipartFile> files = uploadedFiles(request);

        // Absolute file-count guard, applied uniformly (single-file endpoints + pipeline/run too).
        if (files.size() > hardMaxFiles) {
            throw new IllegalArgumentException(
                "Too many files: " + files.size() + " (limit " + hardMaxFiles + " per request).");
        }

        if (!quotaEnabled) return true;

        // Free-tier per-request and per-file caps (stricter than the absolute multipart ceiling).
        if (files.size() > freeMaxFiles) {
            throw new TooLargeException("Free tier allows at most " + freeMaxFiles
                + " files per request; received " + files.size() + ".");
        }
        for (MultipartFile f : files) {
            if (f.getSize() > freeMaxFileBytes) {
                throw new TooLargeException("File \"" + safeName(f)
                    + "\" exceeds the free-tier per-file limit of "
                    + DataSize.ofBytes(freeMaxFileBytes).toMegabytes() + " MB.");
            }
        }

        // Daily free quota.
        String ip = ClientIp.resolve(request);
        long used = quota.used(ip);
        boolean exhausted = used >= quota.dailyLimit();
        // The header must reflect POST-request state: a request that proceeds here will be counted
        // on success (afterCompletion), so advertise the remaining allowance AFTER this op counts.
        // For an exhausted (blocked) request this clamps to 0. The actual count is still incremented
        // only in afterCompletion on a 2xx, so there is no double-counting.
        long remainingAfter = Math.max(0, quota.dailyLimit() - (used + 1));
        response.setHeader("X-Quota-Limit", Integer.toString(quota.dailyLimit()));
        response.setHeader("X-Quota-Remaining", Long.toString(exhausted ? 0 : remainingAfter));
        response.setHeader("X-Quota-Reset", Long.toString(quota.resetEpochSeconds()));
        if (exhausted) {
            JsonErrors.write(response, 429, "quota_exceeded",
                "Daily free limit reached. Please try again after the quota resets.",
                Map.of("resetEpochSeconds", quota.resetEpochSeconds()));
            return false;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (!quotaEnabled) return;
        if (!Endpoints.isQuotaOp(Endpoints.path(request))) return;
        int status = response.getStatus();
        if (status >= 200 && status < 300) {
            quota.increment(ClientIp.resolve(request));
        }
    }

    private static List<MultipartFile> uploadedFiles(HttpServletRequest request) {
        if (!(request instanceof MultipartHttpServletRequest mp)) return List.of();
        return mp.getMultiFileMap().values().stream().flatMap(List::stream).toList();
    }

    private static String safeName(MultipartFile f) {
        String n = f.getOriginalFilename();
        return (n == null || n.isBlank()) ? "upload" : n;
    }
}
