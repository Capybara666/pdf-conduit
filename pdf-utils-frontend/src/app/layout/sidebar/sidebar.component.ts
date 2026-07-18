import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { TranslocoModule } from '@jsverse/transloco';

import { NAV_GROUPS, NAV_ITEMS, NavItem } from '../../core/operations';
import { OpIconComponent } from '../../shared/op-icon/op-icon.component';

/**
 * Left navigation. Lists every operation (grouped) plus Wizard and Pipeline;
 * the router swaps the main view. Collapses to an icon rail on narrow screens.
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

  itemsFor(group: NavItem['group']): NavItem[] {
    return NAV_ITEMS.filter((item) => item.group === group);
  }
}
