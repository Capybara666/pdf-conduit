import { Component, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/** Rotate pages of one or more PDFs by 90/180/270 degrees. */
@Component({
  selector: 'app-rotate-page',
  standalone: true,
  imports: [ReactiveFormsModule, FileDropZoneComponent, PageHeaderComponent, ResultPanelComponent],
  template: `
    <section class="op-page">
      <app-page-header title="Rotate" description="Rotate pages 90°, 180° or 270°." />

      <app-file-drop-zone
        [multiple]="true"
        accept=".pdf"
        hint="One or more PDFs (several files → ZIP)."
        (filesChange)="files.set($event)"
      />

      <div class="card form-grid">
        <div class="field">
          <span class="field-label">Angle</span>
          <div class="seg" role="group" aria-label="Rotation angle">
            @for (a of angles; track a) {
              <button type="button" [class.active]="angle() === a" (click)="angle.set(a)">{{ a }}°</button>
            }
          </div>
        </div>
        <div class="field">
          <label for="rt-pages">Pages</label>
          <input id="rt-pages" type="text" [formControl]="pages" placeholder="e.g. 1,3,5-8 (blank = all)" />
          <span class="help">Blank = every page.</span>
        </div>
      </div>

      <div class="btn-row">
        <button type="button" class="btn btn-primary" [disabled]="!files().length || state.loading()" (click)="submit()">
          Rotate {{ files().length }} file{{ files().length === 1 ? '' : 's' }}
        </button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        loadingLabel="Rotating…"
        [error]="state.error()"
        [result]="state.result()"
      />
    </section>
  `,
})
export class RotatePage {
  protected readonly angles = [90, 180, 270];
  protected readonly files = signal<File[]>([]);
  protected readonly angle = signal(90);
  protected readonly pages = new FormControl('', { nonNullable: true });
  protected readonly state = new OperationState();

  constructor(private readonly api: ApiService) {}

  submit(): void {
    if (!this.files().length) return;
    const fd = new FormData();
    for (const f of this.files()) fd.append('files', f, f.name);
    fd.append('angle', String(this.angle()));
    const p = this.pages.value.trim();
    if (p) fd.append('pages', p);
    this.state.run(this.api.rotate(fd));
  }
}
