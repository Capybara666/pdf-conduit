import { DecimalPipe } from '@angular/common';
import { Component, computed, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { OperationState } from '../../core/operation-state';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
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
    PageHeaderComponent,
    ResultPanelComponent,
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
            <div class="field">
              <label for="wm-position">{{ 'pages.watermark.position' | transloco }}</label>
              <select id="wm-position" [value]="position()" (change)="position.set($any($event.target).value)">
                <option value="center">{{ 'pages.watermark.positionCenter' | transloco }}</option>
                <option value="top-left">{{ 'pages.watermark.positionTopLeft' | transloco }}</option>
                <option value="top-right">{{ 'pages.watermark.positionTopRight' | transloco }}</option>
                <option value="bottom-left">{{ 'pages.watermark.positionBottomLeft' | transloco }}</option>
                <option value="bottom-right">{{ 'pages.watermark.positionBottomRight' | transloco }}</option>
              </select>
            </div>
          }

          @if (mode() === 'text') {
            <div class="field">
              <label for="wm-color">{{ 'pages.watermark.color' | transloco }}</label>
              <input id="wm-color" type="color" [value]="color()" (input)="color.set($any($event.target).value)" />
            </div>
          }

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
      </div>

      <div class="btn-row">
        <button type="button" class="btn btn-primary" [disabled]="!canSubmit() || state.loading()" (click)="submit()">
          {{ 'pages.watermark.submit' | transloco }}
          @if (files().length) {
            · {{ 'common.fileCount' | transloco: { count: files().length } }}
          }
        </button>
        @if (files().length && !hasContent()) {
          <span class="hint-note">{{ (mode() === 'text' ? 'pages.watermark.needText' : 'pages.watermark.needImage') | transloco }}</span>
        }
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.watermark.loading' | transloco"
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

  constructor(private readonly api: ApiService) {}

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
