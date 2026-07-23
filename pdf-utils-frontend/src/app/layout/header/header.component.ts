import {
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnDestroy,
  OnInit,
  Output,
  ViewChild,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoModule } from '@jsverse/transloco';
import { Subscription, switchMap, timer } from 'rxjs';

import { ApiService } from '../../core/api.service';
import { QuotaService } from '../../core/quota.service';
import { WidthMode, WidthService } from '../../core/width.service';
import { SecondaryControlsComponent } from './secondary-controls.component';
import { SettingsModalComponent } from './settings-modal.component';

type HealthState = 'unknown' | 'up' | 'down';

/**
 * App header: brand, live backend-health indicator (polls `GET /api/health`),
 * the free-quota chip, the content-width toggle, and (via
 * {@link SecondaryControlsComponent}) the language + theme pickers. On mobile
 * the width toggle is hidden (wide mode is a desktop feature) and the inline
 * pickers are replaced by a gear button that opens a {@link SettingsModalComponent}
 * sheet, so the top bar never wraps into extra rows.
 */
@Component({
  selector: 'app-header',
  standalone: true,
  imports: [RouterLink, TranslocoModule, SecondaryControlsComponent, SettingsModalComponent],
  templateUrl: './header.component.html',
  styleUrl: './header.component.scss',
})
export class HeaderComponent implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);
  protected readonly quota = inject(QuotaService);
  protected readonly widthService = inject(WidthService);

  /** Reflects the mobile drawer's open state (drives the hamburger's icon/ARIA). */
  @Input() menuOpen = false;
  /** Emitted when the hamburger is activated; the shell owns the drawer state. */
  @Output() menuToggle = new EventEmitter<void>();

  @ViewChild('menuButton') private menuButton?: ElementRef<HTMLButtonElement>;
  @ViewChild('gearButton') private gearButton?: ElementRef<HTMLButtonElement>;

  readonly health = signal<HealthState>('unknown');
  /** Whether the mobile settings sheet (gear) is open. Mobile-only surface. */
  readonly settingsOpen = signal(false);
  private sub?: Subscription;

  /** Return focus to the hamburger when the drawer closes. */
  focusMenu(): void {
    this.menuButton?.nativeElement.focus();
  }

  openSettings(): void {
    this.settingsOpen.set(true);
  }

  /** Close the settings sheet and hand focus back to the gear that opened it. */
  closeSettings(): void {
    this.settingsOpen.set(false);
    setTimeout(() => this.gearButton?.nativeElement.focus());
  }

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

  /** Toggle the global content width mode (fixed ↔ wide). */
  setWidth(mode: WidthMode): void {
    this.widthService.set(mode);
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
