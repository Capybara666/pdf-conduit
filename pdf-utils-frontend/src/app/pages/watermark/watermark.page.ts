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
              <label for="wm-image">{{ 'pages.watermark.image' | transloco }}</label>
              <input id="wm-image" type="file" accept="image/*" (change)="onImage($event)" />
              @if (image()) {
                <span class="help">{{ image()!.name }}</span>
              }
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
  protected readonly state = new OperationState();

  protected readonly hasContent = computed(() =>
    this.mode() === 'text' ? this.textValue().trim().length > 0 : this.image() !== null,
  );
  protected readonly canSubmit = computed(() => this.files().length > 0 && this.hasContent());

  constructor(private readonly api: ApiService) {}

  onImage(ev: Event): void {
    const input = ev.target as HTMLInputElement;
    this.image.set(input.files?.[0] ?? null);
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
    this.state.run(this.api.watermark(fd));
  }
}
