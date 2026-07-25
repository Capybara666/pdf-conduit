import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { WorkStateService } from '../../core/work-state.service';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { OpProgressComponent } from '../../shared/op-progress/op-progress.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

type Mode = 'text' | 'image';
type Layout = 'single' | 'tile' | 'diagonal';
type Position = 'center' | 'top-left' | 'top-right' | 'bottom-left' | 'bottom-right';

/** Stamp text or an image watermark over every page. Exactly one of text/image. */
@Component({
  selector: 'app-watermark-page',
  standalone: true,
  imports: [
    DecimalPipe,
    ReactiveFormsModule,
    TranslocoModule,
    FileDropZoneComponent,
    OpProgressComponent,
    PageHeaderComponent,
    ResultPanelComponent,
  ],
  styles: [
    `
      /* Segmented controls sit inside a flex-column .field, whose default
         align-items:stretch stretched the bordered .seg frame to the full
         field width — leaving a wide empty gap to the right of the buttons.
         Hug the content instead so the frame wraps the buttons tightly
         (matching the type toggle above, which lives in the block-level card),
         and keep the buttons from stretching absurdly on wide screens.
         max-width:100% lets it clamp + wrap on narrow screens. */
      .field > .seg {
        align-self: flex-start;
        max-width: 100%;
      }

      /* Opacity / Rotation / Scale sit together on one 3-across row (spanning
         the full form-grid width), collapsing to fewer columns on narrow
         screens. Color then falls onto its own row below. */
      .opts-row {
        grid-column: 1 / -1;
        display: grid;
        gap: 1.1rem 1.25rem;
        grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      }
    `,
  ],
  template: `
    <section class="op-page">
      <app-page-header
        [title]="'pages.watermark.title' | transloco"
        [description]="'pages.watermark.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="true"
        accept=".pdf"
        [hint]="'pages.watermark.hint' | transloco"
        (filesChange)="files.set($event)"
      />

      <p class="hint-note" role="note">{{ 'pages.watermark.privacyLine' | transloco }}</p>

      <div class="card">
        <div class="seg" role="group" [attr.aria-label]="'pages.watermark.typeAria' | transloco" style="margin-bottom:1rem">
          <button type="button" [class.active]="mode() === 'text'" [attr.aria-pressed]="mode() === 'text'" (click)="mode.set('text')">{{ 'pages.watermark.typeText' | transloco }}</button>
          <button type="button" [class.active]="mode() === 'image'" [attr.aria-pressed]="mode() === 'image'" (click)="mode.set('image')">{{ 'pages.watermark.typeImage' | transloco }}</button>
        </div>

        <div class="form-grid">
          @if (mode() === 'text') {
            <div class="field full">
              <label for="wm-text">{{ 'pages.watermark.text' | transloco }}</label>
              <input
                id="wm-text"
                type="text"
                [formControl]="text"
                [placeholder]="'pages.watermark.textPlaceholder' | transloco"
              />
            </div>
          } @else {
            <div class="field full">
              <span class="field-label" id="wm-image-label">{{ 'pages.watermark.image' | transloco }}</span>
              <app-file-drop-zone
                [multiple]="false"
                accept="image/*"
                [hint]="'pages.watermark.imageHint' | transloco"
                (filesChange)="onImageFiles($event)"
              />
            </div>
          }

          <div class="field full">
            <label>{{ 'pages.watermark.layout' | transloco }}</label>
            <div class="seg" role="group" [attr.aria-label]="'pages.watermark.layout' | transloco">
              <button type="button" [class.active]="layout() === 'single'" [attr.aria-pressed]="layout() === 'single'" (click)="layout.set('single')">{{ 'pages.watermark.layoutSingle' | transloco }}</button>
              <button type="button" [class.active]="layout() === 'tile'" [attr.aria-pressed]="layout() === 'tile'" (click)="layout.set('tile')">{{ 'pages.watermark.layoutTile' | transloco }}</button>
              <button type="button" [class.active]="layout() === 'diagonal'" [attr.aria-pressed]="layout() === 'diagonal'" (click)="layout.set('diagonal')">{{ 'pages.watermark.layoutDiagonal' | transloco }}</button>
            </div>
          </div>

          @if (layout() === 'single') {
            <div class="field full">
              <label>{{ 'pages.watermark.position' | transloco }}</label>
              <div class="seg" role="group" [attr.aria-label]="'pages.watermark.position' | transloco" style="flex-wrap:wrap">
                <button type="button" [class.active]="position() === 'center'" [attr.aria-pressed]="position() === 'center'" (click)="position.set('center')">{{ 'pages.watermark.positionCenter' | transloco }}</button>
                <button type="button" [class.active]="position() === 'top-left'" [attr.aria-pressed]="position() === 'top-left'" (click)="position.set('top-left')">{{ 'pages.watermark.positionTopLeft' | transloco }}</button>
                <button type="button" [class.active]="position() === 'top-right'" [attr.aria-pressed]="position() === 'top-right'" (click)="position.set('top-right')">{{ 'pages.watermark.positionTopRight' | transloco }}</button>
                <button type="button" [class.active]="position() === 'bottom-left'" [attr.aria-pressed]="position() === 'bottom-left'" (click)="position.set('bottom-left')">{{ 'pages.watermark.positionBottomLeft' | transloco }}</button>
                <button type="button" [class.active]="position() === 'bottom-right'" [attr.aria-pressed]="position() === 'bottom-right'" (click)="position.set('bottom-right')">{{ 'pages.watermark.positionBottomRight' | transloco }}</button>
              </div>
            </div>
          }

          <div class="opts-row">
            <div class="field">
              <label for="wm-opacity">{{ 'pages.watermark.opacity' | transloco }} <span class="hint-note">{{ opacity() | number: '1.2-2' }}</span></label>
              <div class="range-row">
                <input id="wm-opacity" type="range" min="0.05" max="1" step="0.05"
                       [value]="opacity()" (input)="opacity.set(+$any($event.target).value)" />
                <output>{{ opacity() | number: '1.2-2' }}</output>
              </div>
            </div>
            <div class="field">
              <label for="wm-rotation">{{ 'pages.watermark.rotation' | transloco }} <span class="hint-note">{{ rotation() }}°</span></label>
              <div class="range-row">
                <input id="wm-rotation" type="range" min="0" max="360" step="5"
                       [value]="rotation()" (input)="rotation.set(+$any($event.target).value)" />
                <output>{{ rotation() }}°</output>
              </div>
            </div>
            <div class="field">
              <label for="wm-scale">{{ 'pages.watermark.scale' | transloco }} <span class="hint-note">{{ scale() | number: '1.2-2' }}</span></label>
              <div class="range-row">
                <input id="wm-scale" type="range" min="0.1" max="2" step="0.05"
                       [value]="scale()" (input)="scale.set(+$any($event.target).value)" />
                <output>{{ scale() | number: '1.2-2' }}</output>
              </div>
            </div>
          </div>

          @if (mode() === 'text') {
            <div class="field">
              <label for="wm-color">{{ 'pages.watermark.color' | transloco }}</label>
              <input id="wm-color" type="color" [value]="color()" (input)="color.set($any($event.target).value)" />
            </div>
          }
        </div>
      </div>

      <div class="btn-row">
        <button type="button" class="btn btn-primary" [disabled]="!canSubmit() || state.loading()" (click)="submit()">
          {{ 'pages.watermark.submit' | transloco }}
          @if (files().length) {
            · {{ 'common.fileCount' | transloco: { count: files().length } }}
          }
        </button>
        <button type="button" class="btn" (click)="clear()">{{ 'common.clear' | transloco }}</button>
        @if (files().length && !hasContent()) {
          <span class="hint-note">{{ (mode() === 'text' ? 'pages.watermark.needText' : 'pages.watermark.needImage') | transloco }}</span>
        }
      </div>

      <app-op-progress
        [run]="state.tracker()"
        [label]="'pages.watermark.loading' | transloco"
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
export class WatermarkPage {
  protected readonly files = signal<File[]>([]);
  protected readonly mode = signal<Mode>('text');
  protected readonly text = new FormControl('', { nonNullable: true });
  /** FormControls aren't signals; mirror the value so computed() reacts to typing. */
  private readonly textValue = toSignal(this.text.valueChanges, { initialValue: this.text.value });
  protected readonly image = signal<File | null>(null);
  protected readonly opacity = signal(0.3);
  protected readonly rotation = signal(45);
  protected readonly scale = signal(0.5);
  protected readonly layout = signal<Layout>('single');
  protected readonly position = signal<Position>('center');
  protected readonly color = signal('#999999');
  protected readonly state = new OperationState();

  protected readonly hasContent = computed(() =>
    this.mode() === 'text' ? this.textValue().trim().length > 0 : this.image() !== null,
  );
  protected readonly canSubmit = computed(() => this.files().length > 0 && this.hasContent());
  private readonly workState = inject(WorkStateService);

  constructor(private readonly api: ApiService) {
    this.workState.persist('watermark', {
      mode: this.mode,
      text: this.text,
      opacity: this.opacity,
      rotation: this.rotation,
      scale: this.scale,
      layout: this.layout,
      position: this.position,
      color: this.color,
    });
  }

  clear(): void {
    this.workState.reset('watermark');
    this.files.set([]);
    this.image.set(null);
    this.state.reset();
  }

  onImageFiles(files: File[]): void {
    this.image.set(files[0] ?? null);
  }

  submit(): void {
    if (!this.canSubmit()) return;
    const fd = new FormData();
    for (const f of this.files()) fd.append('files', f, f.name);
    // Exactly one of text / image — the backend rejects both or neither.
    if (this.mode() === 'text') {
      fd.append('text', this.text.value.trim());
    } else if (this.image()) {
      fd.append('image', this.image()!, this.image()!.name);
    }
    fd.append('opacity', String(this.opacity()));
    fd.append('rotation', String(this.rotation()));
    fd.append('scale', String(this.scale()));
    fd.append('layout', this.layout());
    if (this.layout() === 'single') fd.append('position', this.position());
    if (this.mode() === 'text') fd.append('color', this.color());
    this.state.run(this.api.watermark(fd));
  }
}
