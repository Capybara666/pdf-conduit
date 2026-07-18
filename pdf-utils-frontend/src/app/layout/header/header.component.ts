import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoModule } from '@jsverse/transloco';
import { Subscription, switchMap, timer } from 'rxjs';

import { ApiService } from '../../core/api.service';
import { LanguageService } from '../../core/i18n/language.service';
import { QuotaService } from '../../core/quota.service';
import { ThemeService } from '../../core/theme.service';

type HealthState = 'unknown' | 'up' | 'down';

/**
 * App header: brand, live backend-health indicator (polls `GET /api/health`),
 * a language picker and a light/dark theme toggle.
 */
@Component({
  selector: 'app-header',
  standalone: true,
  imports: [RouterLink, TranslocoModule],
  templateUrl: './header.component.html',
  styleUrl: './header.component.scss',
})
export class HeaderComponent implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);
  protected readonly themeService = inject(ThemeService);
  protected readonly quota = inject(QuotaService);
  protected readonly language = inject(LanguageService);

  readonly health = signal<HealthState>('unknown');
  private sub?: Subscription;

  /** State of the quota chip: normal / low / spent — drives its styling. */
  get quotaState(): 'ok' | 'low' | 'spent' {
    if (this.quota.exhausted()) return 'spent';
    if (this.quota.low()) return 'low';
    return 'ok';
  }

  ngOnInit(): void {
    // Poll every 15s (immediately on start).
    this.sub = timer(0, 15000)
      .pipe(switchMap(() => this.api.getHealth()))
      .subscribe({
        next: (h) => this.health.set(h.status?.toUpperCase() === 'UP' ? 'up' : 'down'),
        error: () => this.health.set('down'),
      });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  toggleTheme(): void {
    this.themeService.toggle();
  }

  onLangChange(event: Event): void {
    this.language.setLang((event.target as HTMLSelectElement).value);
  }

  /** i18n key for the current backend-health label. */
  get healthKey(): string {
    switch (this.health()) {
      case 'up':
        return 'header.serverOnline';
      case 'down':
        return 'header.serverOffline';
      default:
        return 'header.serverChecking';
    }
  }
}
