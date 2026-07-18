package com.pdfconduit.web.principal;

import com.pdfconduit.web.support.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * The only {@link PrincipalResolver} today: every caller is anonymous and identified by the client
 * IP that {@link ClientIp} resolves (rightmost-untrusted XFF token behind trusted proxies). This
 * preserves the exact keying the rate-limit filter and quota interceptor used before the seam.
 */
@Component
public class IpPrincipalResolver implements PrincipalResolver {

    private final ClientIp clientIp;

    public IpPrincipalResolver(ClientIp clientIp) {
        this.clientIp = clientIp;
    }

    @Override
    public RequestPrincipal resolve(HttpServletRequest request) {
        return new IpPrincipal(clientIp.resolve(request));
    }
}
