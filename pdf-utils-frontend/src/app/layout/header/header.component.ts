import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Subscription, switchMap, timer } from 'rxjs';

import { ApiService } from '../../core/api.service';
import { ThemeService } from '../../core/theme.service';

type HealthState = 'unknown' | 'up' | 'down';

/**
 * App header: brand, live backend-health indicator (polls `GET /api/health`)
 * and a light/dark theme toggle.
 */
@Component({
  selector: 'app-header',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './header.component.html',
  styleUrl: './header.component.scss',
})
export class HeaderComponent implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);
  protected readonly themeService = inject(ThemeService);

  readonly health = signal<HealthState>('unknown');
  private sub?: Subscription;

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

  get healthLabel(): string {
    switch (this.health()) {
      case 'up':
        return 'Server online';
      case 'down':
        return 'Server offline';
      default:
        return 'Checking server…';
    }
  }
}
