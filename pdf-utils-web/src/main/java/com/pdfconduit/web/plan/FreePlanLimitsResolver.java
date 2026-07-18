package com.pdfconduit.web.plan;

import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.principal.RequestPrincipal;
import org.springframework.stereotype.Component;

/**
 * The only {@link PlanLimitsResolver} today: it builds a single FREE {@link PlanLimits} from the
 * configured {@link WebProperties} values (so {@code pdfconduit.web.*} overrides still flow through
 * unchanged) and returns it for every principal. This preserves exactly the ceilings the guards
 * read before the seam; a later resolver keyed on the principal would enable paid tiers with no
 * change to the guards themselves.
 */
@Component
public class FreePlanLimitsResolver implements PlanLimitsResolver {

    private final PlanLimits free;

    public FreePlanLimitsResolver(WebProperties props) {
        this.free = new FreePlanLimits(
            props.quota().dailyOperations(),
            props.quota().freeMaxFiles(),
            props.quota().freeMaxFileSize().toBytes(),
            props.pdf().maxPages(),
            props.render().maxDpi(),
            props.render().maxOutputPixels(),
            props.ratelimit().requestsPerMinute(),
            props.ratelimit().heavyPerMinute(),
            props.ratelimit().burst());
    }

    @Override
    public PlanLimits resolve(RequestPrincipal principal) {
        return free;
    }
}
