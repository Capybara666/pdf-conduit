import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { TranslocoModule } from '@jsverse/transloco';

import { CapabilitiesService } from '../../core/capabilities.service';
import { NAV_GROUPS, NAV_ITEMS, NavItem } from '../../core/operations';
import { OpIconComponent } from '../../shared/op-icon/op-icon.component';

/**
 * Left navigation. Lists every operation (grouped) plus Wizard and Pipeline;
 * the router swaps the main view. Below the mobile breakpoint it becomes the
 * off-canvas drawer. The language + theme pickers are NOT here — on mobile they
 * live in the header's settings sheet (gear button), a single findable home.
 *
 * Operations the server catalog flags `available: false` (e.g. OCR on a server
 * with OCR disabled) are NOT rendered; if the catalog fetch fails, everything
 * is shown (fail-open — see {@link CapabilitiesService}).
 */
@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, TranslocoModule, OpIconComponent],
  templateUrl: './sidebar.component.html',
  styleUrl: './sidebar.component.scss',
})
export class SidebarComponent {
  protected readonly groups = NAV_GROUPS;
  private readonly capabilities = inject(CapabilitiesService);

  itemsFor(group: NavItem['group']): NavItem[] {
    return NAV_ITEMS.filter(
      (item) => item.group === group && this.capabilities.isAvailable(item.id),
    );
  }
}
