import { Component, computed, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { WorkStateService } from '../../core/work-state.service';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { OpProgressComponent } from '../../shared/op-progress/op-progress.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/**
 * Stamp page numbers and/or header & footer text onto every page. Six independent slots
 * (header/footer × left/center/right) accept free text with the tokens {page}, {n}, {pages}
 * and {date}; a non-blank Bates prefix switches numbers to prefix + zero-padded form.
 */
@Component({
  selector: 'app-page-marks-page',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    TranslocoModule,
    FileDropZoneComponent,
    OpProgressComponent,
    PageHeaderComponent,
    ResultPanelComponent,
  ],
  styles: [
    `
      .group-title {
        display: block;
        margin: 0 0 0.6rem;
        padding: 0;
        color: var(--text);
        font-size: 1.05rem;
        font-weight: 700;
        letter-spacing: 0.01em;
      }
    `,
  ],
  template: `
    <section class="op-page">
      <app-page-header
        [title]="'pages.pageMarks.title' | transloco"
        [description]="'pages.pageMarks.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="true"
        accept=".pdf"
        [hint]="'pages.pageMarks.hint' | transloco"
        (filesChange)="files.set($event)"
      />

      <p class="hint-note" role="note">{{ 'pages.pageMarks.privacyLine' | transloco }}</p>

      <div class="card">
        <p class="hint-note">{{ 'pages.pageMarks.tokensHelp' | transloco }}</p>

        <fieldset style="border:0;padding:0;margin:0;min-inline-size:0">
          <legend class="group-title">{{ 'pages.pageMarks.header' | transloco }}</legend>
          <div class="form-grid">
            <div class="field">
              <label for="hl">{{ 'pages.pageMarks.left' | transloco }}</label>
              <input id="hl" type="text" [formControl]="headerLeft" [placeholder]="'pages.pageMarks.slotPlaceholder' | transloco" />
            </div>
            <div class="field">
              <label for="hc">{{ 'pages.pageMarks.center' | transloco }}</label>
              <input id="hc" type="text" [formControl]="headerCenter" [placeholder]="'pages.pageMarks.slotPlaceholder' | transloco" />
            </div>
            <div class="field">
              <label for="hr">{{ 'pages.pageMarks.right' | transloco }}</label>
              <input id="hr" type="text" [formControl]="headerRight" [placeholder]="'pages.pageMarks.slotPlaceholder' | transloco" />
            </div>
          </div>
        </fieldset>

        <fieldset style="border:0;padding:0;margin:1rem 0 0;min-inline-size:0">
          <legend class="group-title">{{ 'pages.pageMarks.footer' | transloco }}</legend>
          <div class="form-grid">
            <div class="field">
              <label for="fl">{{ 'pages.pageMarks.left' | transloco }}</label>
              <input id="fl" type="text" [formControl]="footerLeft" [placeholder]="'pages.pageMarks.slotPlaceholder' | transloco" />
            </div>
            <div class="field">
              <label for="fc">{{ 'pages.pageMarks.center' | transloco }}</label>
              <input id="fc" type="text" [formControl]="footerCenter" [placeholder]="'pages.pageMarks.slotPlaceholder' | transloco" />
            </div>
            <div class="field">
              <label for="fr">{{ 'pages.pageMarks.right' | transloco }}</label>
              <input id="fr" type="text" [formControl]="footerRight" [placeholder]="'pages.pageMarks.slotPlaceholder' | transloco" />
            </div>
          </div>
        </fieldset>

        <div class="form-grid" style="margin-top:1rem">
          <div class="field">
            <label for="pm-font">{{ 'pages.pageMarks.fontSize' | transloco }}</label>
            <input id="pm-font" type="number" min="6" max="48" step="1" [value]="fontSize()" (input)="fontSize.set(+$any($event.target).value)" />
          </div>
          <div class="field">
            <label for="pm-margin">{{ 'pages.pageMarks.margin' | transloco }}</label>
            <input id="pm-margin" type="number" min="0" max="200" step="1" [value]="margin()" (input)="margin.set(+$any($event.target).value)" />
          </div>
          <div class="field">
            <label for="pm-start">{{ 'pages.pageMarks.startNumber' | transloco }}</label>
            <input id="pm-start" type="number" step="1" [value]="startNumber()" (input)="startNumber.set(+$any($event.target).value)" />
          </div>
          <div class="field">
            <label for="pm-prefix">{{ 'pages.pageMarks.prefix' | transloco }}</label>
            <input id="pm-prefix" type="text" [formControl]="prefix" [placeholder]="'pages.pageMarks.prefixPlaceholder' | transloco" />
          </div>
          <div class="field full">
            <label class="checkbox">
              <input type="checkbox" [checked]="skipFirst()" (change)="skipFirst.set($any($event.target).checked)" />
              {{ 'pages.pageMarks.skipFirst' | transloco }}
            </label>
          </div>
        </div>
      </div>

      <div class="btn-row">
        <button type="button" class="btn btn-primary" [disabled]="!canSubmit() || state.loading()" (click)="submit()">
          {{ 'pages.pageMarks.submit' | transloco }}
          @if (files().length) {
            · {{ 'common.fileCount' | transloco: { count: files().length } }}
          }
        </button>
        <button type="button" class="btn" (click)="clear()">{{ 'common.clear' | transloco }}</button>
        @if (files().length && !hasContent()) {
          <span class="hint-note">{{ 'pages.pageMarks.needSlot' | transloco }}</span>
        }
      </div>

      <app-op-progress
        [run]="state.tracker()"
        [label]="'pages.pageMarks.loading' | transloco"
        (cancel)="state.cancel()"
        (dismiss)="state.dismiss()"
      />

      <app-result-panel
        [error]="state.error()"
        [result]="state.result()"
        (retry)="submit()"
      />
    </section>
  `,
})
export class PageMarksPage {
  protected readonly files = signal<File[]>([]);
  protected readonly headerLeft = new FormControl('', { nonNullable: true });
  protected readonly headerCenter = new FormControl('', { nonNullable: true });
  protected readonly headerRight = new FormControl('', { nonNullable: true });
  protected readonly footerLeft = new FormControl('', { nonNullable: true });
  protected readonly footerCenter = new FormControl('{page} / {pages}', { nonNullable: true });
  protected readonly footerRight = new FormControl('', { nonNullable: true });
  protected readonly prefix = new FormControl('', { nonNullable: true });
  protected readonly fontSize = signal(10);
  protected readonly margin = signal(36);
  protected readonly startNumber = signal(1);
  protected readonly skipFirst = signal(false);
  protected readonly state = new OperationState();
  private readonly workState = inject(WorkStateService);

  /** Mirror each slot's value so `computed()` reacts to typing. */
  private readonly slots = [
    toSignal(this.headerLeft.valueChanges, { initialValue: this.headerLeft.value }),
    toSignal(this.headerCenter.valueChanges, { initialValue: this.headerCenter.value }),
    toSignal(this.headerRight.valueChanges, { initialValue: this.headerRight.value }),
    toSignal(this.footerLeft.valueChanges, { initialValue: this.footerLeft.value }),
    toSignal(this.footerCenter.valueChanges, { initialValue: this.footerCenter.value }),
    toSignal(this.footerRight.valueChanges, { initialValue: this.footerRight.value }),
  ];

  protected readonly hasContent = computed(() => this.slots.some((s) => s().trim().length > 0));
  protected readonly canSubmit = computed(() => this.files().length > 0 && this.hasContent());

  constructor(private readonly api: ApiService) {
    this.workState.persist('page-marks', {
      headerLeft: this.headerLeft,
      headerCenter: this.headerCenter,
      headerRight: this.headerRight,
      footerLeft: this.footerLeft,
      footerCenter: this.footerCenter,
      footerRight: this.footerRight,
      prefix: this.prefix,
      fontSize: this.fontSize,
      margin: this.margin,
      startNumber: this.startNumber,
      skipFirst: this.skipFirst,
    });
  }

  clear(): void {
    this.workState.reset('page-marks');
    this.files.set([]);
    this.state.reset();
  }

  submit(): void {
    if (!this.canSubmit()) return;
    const fd = new FormData();
    for (const f of this.files()) fd.append('files', f, f.name);
    fd.append('headerLeft', this.headerLeft.value);
    fd.append('headerCenter', this.headerCenter.value);
    fd.append('headerRight', this.headerRight.value);
    fd.append('footerLeft', this.footerLeft.value);
    fd.append('footerCenter', this.footerCenter.value);
    fd.append('footerRight', this.footerRight.value);
    fd.append('fontSize', String(this.fontSize()));
    fd.append('margin', String(this.margin()));
    fd.append('startNumber', String(this.startNumber()));
    fd.append('prefix', this.prefix.value);
    fd.append('skipFirst', String(this.skipFirst()));
    this.state.run(this.api.pageMarks(fd));
  }
}
