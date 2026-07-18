import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoModule } from '@jsverse/transloco';

import { OnboardingService } from '../../core/onboarding.service';

/**
 * One-time, dismissible welcome strip shown at the top of the content area
 * until the visitor dismisses it (persisted via {@link OnboardingService}).
 *
 * Deliberately NON-MODAL: it is a slim in-flow `role="region"` banner, not a
 * dialog. It never traps focus, never locks scroll, and never steals focus on
 * load — it simply scrolls away with the page. The header "Getting started"
 * link keeps the guide re-discoverable so this banner can be strictly
 * once-only.
 */
@Component({
  selector: 'app-welcome-banner',
  standalone: true,
  imports: [RouterLink, TranslocoModule],
  templateUrl: './welcome-banner.component.html',
  styleUrl: './welcome-banner.component.scss',
})
export class WelcomeBannerComponent {
  protected readonly onboarding = inject(OnboardingService);
}
