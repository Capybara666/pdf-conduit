import { Component, ElementRef, ViewChild, inject, signal } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { FormField } from '../../core/api.models';
import { OperationState } from '../../core/operation-state';
import { WorkStateService } from '../../core/work-state.service';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { PdfViewerComponent, RegionRect } from '../../shared/pdf-viewer/pdf-viewer.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

type SigMode = 'draw' | 'type' | 'upload';

/**
 * Fill & Sign (Phase 1, visual): the user builds a signature — draw it on a canvas,
 * type a name in a script style, or upload a PNG — then drops boxes on the PDF pages to
 * place it, and optionally flattens. The viewer emits each box as a `RegionRect` in PDF
 * points (top-left, 0-based page), matching the backend `SignPlacementDto` exactly, so
 * every drawn box becomes a placement of the single signature image (imageIndex 0).
 */
@Component({
  selector: 'app-sign-page',
  standalone: true,
  imports: [
    TranslocoModule,
    FileDropZoneComponent,
    PageHeaderComponent,
    PdfViewerComponent,
    ResultPanelComponent,
  ],
  template: `
    <section class="op-page">
      <app-page-header
        [title]="'pages.sign.title' | transloco"
        [description]="'pages.sign.description' | transloco"
      />

      @if (!file()) {
        <app-file-drop-zone
          [multiple]="false"
          accept=".pdf"
          [hint]="'pages.sign.hint' | transloco"
          (filesChange)="onFile($event.length ? $event[0] : null)"
        />
      }

      @if (file()) {
        <div class="sign-layout">
          <div class="viewer-col card">
            <div class="btn-row" style="margin-bottom:0.75rem">
              <strong class="fname">{{ file()!.name }}</strong>
              <span class="hint-note">{{ 'pages.sign.dragHint' | transloco }}</span>
              <button type="button" class="btn btn-ghost" (click)="reset()">
                {{ 'pages.sign.chooseAnother' | transloco }}
              </button>
            </div>
            @if (!signature()) {
              <p class="hint-note">{{ 'pages.sign.needSignature' | transloco }}</p>
            }
            <app-pdf-viewer
              #viewer
              [file]="file()"
              [drawable]="!!signature()"
              [scale]="1.3"
              (regionsChange)="onRegions($event)"
            />
          </div>

          <aside class="side">
            <!-- Signature builder -->
            <div class="card">
              <h2 class="side-title">{{ 'pages.sign.signature' | transloco }}</h2>
              <div class="tabs" role="tablist">
                <button
                  type="button"
                  class="tab"
                  [class.active]="mode() === 'draw'"
                  (click)="setMode('draw')"
                >
                  {{ 'pages.sign.tabDraw' | transloco }}
                </button>
                <button
                  type="button"
                  class="tab"
                  [class.active]="mode() === 'type'"
                  (click)="setMode('type')"
                >
                  {{ 'pages.sign.tabType' | transloco }}
                </button>
                <button
                  type="button"
                  class="tab"
                  [class.active]="mode() === 'upload'"
                  (click)="setMode('upload')"
                >
                  {{ 'pages.sign.tabUpload' | transloco }}
                </button>
              </div>

              @if (mode() === 'draw') {
                <canvas
                  #pad
                  class="pad"
                  width="460"
                  height="150"
                  (pointerdown)="padDown($event)"
                  (pointermove)="padMove($event)"
                  (pointerup)="padUp($event)"
                  (pointerleave)="padUp($event)"
                ></canvas>
                <div class="btn-row">
                  <button type="button" class="btn btn-ghost" (click)="clearPad()">
                    {{ 'pages.sign.clearPad' | transloco }}
                  </button>
                  <button type="button" class="btn btn-primary" (click)="applyDraw()">
                    {{ 'pages.sign.useSignature' | transloco }}
                  </button>
                </div>
              }

              @if (mode() === 'type') {
                <input
                  class="input"
                  type="text"
                  [value]="typed()"
                  (input)="typed.set($any($event.target).value)"
                  [placeholder]="'pages.sign.typePlaceholder' | transloco"
                />
                @if (typed().trim()) {
                  <div class="type-preview">{{ typed() }}</div>
                }
                <div class="btn-row">
                  <button
                    type="button"
                    class="btn btn-primary"
                    [disabled]="!typed().trim()"
                    (click)="applyTyped()"
                  >
                    {{ 'pages.sign.useSignature' | transloco }}
                  </button>
                </div>
              }

              @if (mode() === 'upload') {
                <input type="file" accept="image/png,image/jpeg" (change)="onUpload($event)" />
              }

              @if (signature()) {
                <div class="sig-current">
                  <span class="hint-note">{{ 'pages.sign.currentSignature' | transloco }}</span>
                  <img class="sig-preview" [src]="signature()!.url" alt="" />
                </div>
              }
            </div>

            <!-- Detected AcroForm fields -->
            @if (formFields().length) {
              <div class="card">
                <h2 class="side-title">{{ 'pages.sign.formFields' | transloco }}</h2>
                <p class="hint-note">{{ 'pages.sign.formFieldsHint' | transloco }}</p>
                @for (fld of formFields(); track fld.name) {
                  <div class="field-row">
                    <label class="field-label" [title]="fld.name">{{ fld.name }}</label>
                    @switch (fld.type) {
                      @case ('checkbox') {
                        <label class="check field-check">
                          <input
                            type="checkbox"
                            [disabled]="fld.readOnly"
                            [checked]="isChecked(fld.name)"
                            (change)="setField(fld.name, $any($event.target).checked ? 'true' : 'false')"
                          />
                        </label>
                      }
                      @case ('choice') {
                        <select
                          class="input"
                          [disabled]="fld.readOnly"
                          (change)="setField(fld.name, $any($event.target).value)"
                        >
                          @for (opt of fld.options || []; track opt) {
                            <option [value]="opt" [selected]="valueOf(fld.name) === opt">{{ opt }}</option>
                          }
                        </select>
                      }
                      @case ('radio') {
                        <div class="radio-group">
                          @for (opt of fld.options || []; track opt) {
                            <label class="radio">
                              <input
                                type="radio"
                                [name]="'ff-' + fld.name"
                                [disabled]="fld.readOnly"
                                [checked]="valueOf(fld.name) === opt"
                                (change)="setField(fld.name, opt)"
                              />
                              <span>{{ opt }}</span>
                            </label>
                          }
                        </div>
                      }
                      @default {
                        <input
                          class="input"
                          type="text"
                          [disabled]="fld.readOnly || (fld.type !== 'text')"
                          [value]="valueOf(fld.name)"
                          (input)="setField(fld.name, $any($event.target).value)"
                        />
                      }
                    }
                  </div>
                }
                <label class="check">
                  <input
                    type="checkbox"
                    [checked]="fillFlatten()"
                    (change)="fillFlatten.set($any($event.target).checked)"
                  />
                  <span>{{ 'pages.sign.fillFlatten' | transloco }}</span>
                </label>
                <button
                  type="button"
                  class="btn btn-primary"
                  style="margin-top:0.5rem"
                  [disabled]="state.loading()"
                  (click)="fillForm()"
                >
                  {{ 'pages.sign.fillForm' | transloco }}
                </button>
              </div>
            } @else if (fieldsChecked() && !fieldsLoading()) {
              <p class="hint-note no-fields">{{ 'pages.sign.noFields' | transloco }}</p>
            }

            <!-- Placement + options -->
            <div class="card">
              <h2 class="side-title">
                {{ 'pages.sign.placements' | transloco: { count: regions().length } }}
              </h2>
              @if (!signature()) {
                <p class="hint-note">{{ 'pages.sign.placementsNoSig' | transloco }}</p>
              } @else if (!regions().length) {
                <p class="hint-note">{{ 'pages.sign.placementsEmpty' | transloco }}</p>
              } @else {
                <ul class="region-list">
                  @for (r of regions(); track $index; let i = $index) {
                    <li>
                      <span class="rmeta">{{ 'viewer.pageCaption' | transloco: { n: r.pageIndex + 1 } }}</span>
                      <button
                        type="button"
                        class="icon-btn"
                        (click)="removeRegion(i)"
                        [attr.aria-label]="'pages.sign.removePlacement' | transloco"
                      >
                        ✕
                      </button>
                    </li>
                  }
                </ul>
                <button type="button" class="btn btn-ghost" (click)="clear()">
                  {{ 'common.clearAll' | transloco }}
                </button>
              }

              <label class="check">
                <input type="checkbox" [checked]="flatten()" (change)="flatten.set($any($event.target).checked)" />
                <span>{{ 'pages.sign.flatten' | transloco }}</span>
              </label>
              <p class="hint-note">{{ 'pages.sign.flattenHint' | transloco }}</p>
            </div>

            <div class="btn-row">
              <button
                type="button"
                class="btn btn-primary"
                [disabled]="!signature() || !regions().length || state.loading()"
                (click)="submit()"
              >
                {{ 'pages.sign.submit' | transloco }}
              </button>
              <button type="button" class="btn" (click)="clearWork()">{{ 'common.clear' | transloco }}</button>
            </div>

            <app-result-panel
              [loading]="state.loading()"
              [loadingLabel]="'pages.sign.loading' | transloco"
              [error]="state.error()"
              [result]="state.result()"
              (retry)="submit()"
            />
          </aside>
        </div>
      }
    </section>
  `,
  styles: [
    `
      .sign-layout {
        display: grid;
        grid-template-columns: minmax(0, 1fr) 320px;
        gap: 1.25rem;
        align-items: start;
      }
      .viewer-col {
        max-height: 80vh;
        overflow: auto;
      }
      .side {
        display: flex;
        flex-direction: column;
        gap: 1rem;
        position: sticky;
        top: 1rem;
      }
      .side-title {
        margin: 0 0 0.75rem;
        font-size: 1rem;
      }
      .fname {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        max-width: 40%;
      }
      .tabs {
        display: flex;
        gap: 0.25rem;
        margin-bottom: 0.75rem;
      }
      .tab {
        flex: 1;
        padding: 0.4rem 0.5rem;
        border: 1px solid var(--border);
        background: var(--surface-2);
        border-radius: 6px;
        cursor: pointer;
        color: var(--text);
        font-size: 0.85rem;
      }
      .tab.active {
        background: var(--accent);
        color: #fff;
        border-color: var(--accent);
      }
      .pad {
        width: 100%;
        height: 150px;
        border: 1px dashed var(--border);
        border-radius: 6px;
        background: var(--surface);
        touch-action: none;
        cursor: crosshair;
      }
      .input {
        width: 100%;
        padding: 0.5rem;
        border: 1px solid var(--border);
        border-radius: 6px;
        background: var(--surface);
        color: var(--text);
      }
      .type-preview {
        margin-top: 0.5rem;
        padding: 0.5rem;
        font-family: 'Segoe Script', 'Brush Script MT', cursive;
        font-size: 2rem;
        color: var(--text);
        border-bottom: 1px solid var(--border);
        overflow: hidden;
        white-space: nowrap;
      }
      .sig-current {
        margin-top: 0.75rem;
        display: flex;
        flex-direction: column;
        gap: 0.35rem;
      }
      .sig-preview {
        max-width: 100%;
        max-height: 90px;
        border: 1px solid var(--border);
        border-radius: 6px;
        background: repeating-conic-gradient(#0000 0 25%, #8883 0 50%) 0 0 / 16px 16px;
      }
      .region-list {
        list-style: none;
        margin: 0 0 0.75rem;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 0.35rem;
      }
      .region-list li {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 0.3rem 0.5rem;
        background: var(--surface-2);
        border-radius: 6px;
      }
      .rmeta {
        font-size: 0.8rem;
        font-variant-numeric: tabular-nums;
      }
      .check {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        margin-top: 0.75rem;
        cursor: pointer;
      }
      .field-row {
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
        margin-bottom: 0.6rem;
      }
      .field-label {
        font-size: 0.8rem;
        color: var(--text-muted, var(--text));
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .field-check {
        margin-top: 0;
      }
      .radio-group {
        display: flex;
        flex-wrap: wrap;
        gap: 0.5rem 1rem;
      }
      .radio {
        display: flex;
        align-items: center;
        gap: 0.35rem;
        font-size: 0.85rem;
        cursor: pointer;
      }
      .no-fields {
        margin: 0;
      }
      @media (max-width: 820px) {
        .sign-layout {
          grid-template-columns: 1fr;
        }
        .side {
          position: static;
        }
      }
      @media (max-width: 640px) {
        .viewer-col {
          max-height: 60vh;
        }
        .fname {
          max-width: 100%;
        }
      }
    `,
  ],
})
export class SignPage {
  @ViewChild('viewer') private viewer?: PdfViewerComponent;
  @ViewChild('pad') private pad?: ElementRef<HTMLCanvasElement>;

  protected readonly file = signal<File | null>(null);
  protected readonly regions = signal<RegionRect[]>([]);
  protected readonly mode = signal<SigMode>('draw');
  protected readonly typed = signal('');
  protected readonly flatten = signal(false);
  protected readonly signature = signal<{ file: File; url: string } | null>(null);
  protected readonly state = new OperationState();

  /** Detected AcroForm fields (empty = none / not yet loaded). */
  protected readonly formFields = signal<FormField[]>([]);
  /** Working name→value map the field controls edit; submitted to the fill endpoint. */
  protected readonly fieldValues = signal<Record<string, string>>({});
  /** True while the detection request is in flight. */
  protected readonly fieldsLoading = signal(false);
  /** True once detection has completed (so the "no fields" note only shows after a real check). */
  protected readonly fieldsChecked = signal(false);
  /** Flatten toggle for the form-fill action (independent of the signature flatten). */
  protected readonly fillFlatten = signal(false);

  /** Aspect ratio (width / height) of the loaded signature image, or null until
   *  it has decoded. Every placement box is constrained to this so the drawn box
   *  always matches the signature's shape (no distortion, no letterbox gap). */
  private readonly sigAspect = signal<number | null>(null);
  /** Guards the re-entrant `regionsChange` the viewer fires from `setRegions`. */
  private snapping = false;

  private drawing = false;
  private last: { x: number; y: number } | null = null;
  private padDirty = false;
  private readonly workState = inject(WorkStateService);

  constructor(private readonly api: ApiService) {
    this.workState.persist('sign', { mode: this.mode, flatten: this.flatten });
  }

  /** Explicit Clear: drop the file, regions, signature, options and result. */
  clearWork(): void {
    this.workState.reset('sign');
    this.onFile(null);
  }

  onFile(f: File | null): void {
    this.file.set(f);
    this.regions.set([]);
    this.signature.set(null);
    this.sigAspect.set(null);
    this.typed.set('');
    this.flatten.set(false);
    this.formFields.set([]);
    this.fieldValues.set({});
    this.fieldsChecked.set(false);
    this.fieldsLoading.set(false);
    this.fillFlatten.set(false);
    this.state.reset();
    if (f) this.detectFields(f);
  }

  // ---- Form fields (detect + fill) --------------------------------------

  /** Call `/api/form-fields` for the uploaded PDF and seed the editable values. */
  private detectFields(f: File): void {
    this.fieldsLoading.set(true);
    this.fieldsChecked.set(false);
    this.api.formFields(f).subscribe({
      next: (fields) => {
        if (this.file() !== f) return; // superseded by a newer file
        this.formFields.set(fields);
        const values: Record<string, string> = {};
        for (const fld of fields) values[fld.name] = fld.value ?? '';
        this.fieldValues.set(values);
        this.fieldsLoading.set(false);
        this.fieldsChecked.set(true);
      },
      error: () => {
        if (this.file() !== f) return;
        this.formFields.set([]);
        this.fieldsLoading.set(false);
        this.fieldsChecked.set(true);
      },
    });
  }

  valueOf(name: string): string {
    return this.fieldValues()[name] ?? '';
  }

  isChecked(name: string): boolean {
    const v = this.valueOf(name).trim().toLowerCase();
    return v === 'true' || v === 'yes' || v === 'on' || v === '1' || v === 'x' || v === 'checked';
  }

  setField(name: string, value: string): void {
    this.fieldValues.set({ ...this.fieldValues(), [name]: value });
  }

  /** Submit the collected field values to the fill endpoint and download the filled PDF. */
  fillForm(): void {
    const f = this.file();
    if (!f || !this.formFields().length) return;
    const fd = new FormData();
    fd.append('file', f, f.name);
    fd.append('fields', JSON.stringify(this.fieldValues()));
    fd.append('flatten', String(this.fillFlatten()));
    this.state.run(this.api.sign(fd));
  }

  // ---- Placement aspect constraint --------------------------------------

  /**
   * Every box the viewer emits is constrained to the signature image's aspect
   * ratio, then pushed BACK into the viewer via `setRegions` so the on-screen
   * overlay AND the submitted region are identical, aspect-correct rectangles
   * (matching the backend's fit-inside render — no distortion, no letterbox).
   */
  onRegions(rs: RegionRect[]): void {
    if (this.snapping) return; // ignore the re-entrant emit from setRegions
    const aspect = this.sigAspect();
    if (!aspect || !rs.length) {
      this.regions.set(rs);
      return;
    }
    const snapped = rs.map((r) => this.snapToAspect(r, aspect));
    this.regions.set(snapped);
    if (this.regionsChanged(rs, snapped)) {
      this.snapping = true;
      this.viewer?.setRegions(snapped);
      this.snapping = false;
    }
  }

  /** Fit an aspect-correct rectangle inside the drawn box, anchored top-left. */
  private snapToAspect(r: RegionRect, aspect: number): RegionRect {
    if (!(aspect > 0) || r.width <= 0 || r.height <= 0) return r;
    let width = r.width;
    let height = r.height;
    if (r.width / r.height > aspect) {
      width = r.height * aspect; // too wide → height is limiting
    } else {
      height = r.width / aspect; // too tall → width is limiting
    }
    return { ...r, width: +width.toFixed(2), height: +height.toFixed(2) };
  }

  private regionsChanged(a: RegionRect[], b: RegionRect[]): boolean {
    if (a.length !== b.length) return true;
    for (let i = 0; i < a.length; i++) {
      if (Math.abs(a[i].width - b[i].width) > 0.05 || Math.abs(a[i].height - b[i].height) > 0.05) {
        return true;
      }
    }
    return false;
  }

  /** Re-constrain existing placements after the signature (aspect) changes. */
  private resnapAll(): void {
    const aspect = this.sigAspect();
    const cur = this.regions();
    if (!aspect || !cur.length) return;
    const snapped = cur.map((r) => this.snapToAspect(r, aspect));
    if (!this.regionsChanged(cur, snapped)) return;
    this.regions.set(snapped);
    this.snapping = true;
    this.viewer?.setRegions(snapped);
    this.snapping = false;
  }

  reset(): void {
    this.onFile(null);
  }

  setMode(m: SigMode): void {
    this.mode.set(m);
  }

  removeRegion(i: number): void {
    this.viewer?.removeRegion(i);
  }

  clear(): void {
    this.viewer?.clearRegions();
  }

  // ---- Draw pad ---------------------------------------------------------

  private padCtx(): CanvasRenderingContext2D | null {
    const c = this.pad?.nativeElement;
    return c ? c.getContext('2d') : null;
  }

  private padPoint(ev: PointerEvent): { x: number; y: number } {
    const c = this.pad!.nativeElement;
    const rect = c.getBoundingClientRect();
    return {
      x: ((ev.clientX - rect.left) / rect.width) * c.width,
      y: ((ev.clientY - rect.top) / rect.height) * c.height,
    };
  }

  padDown(ev: PointerEvent): void {
    (ev.target as HTMLElement).setPointerCapture?.(ev.pointerId);
    this.drawing = true;
    this.last = this.padPoint(ev);
  }

  padMove(ev: PointerEvent): void {
    if (!this.drawing || !this.last) return;
    const ctx = this.padCtx();
    if (!ctx) return;
    const p = this.padPoint(ev);
    ctx.strokeStyle = '#12294d';
    ctx.lineWidth = 2.5;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();
    ctx.moveTo(this.last.x, this.last.y);
    ctx.lineTo(p.x, p.y);
    ctx.stroke();
    this.last = p;
    this.padDirty = true;
  }

  padUp(_ev: PointerEvent): void {
    this.drawing = false;
    this.last = null;
  }

  clearPad(): void {
    const ctx = this.padCtx();
    const c = this.pad?.nativeElement;
    if (ctx && c) ctx.clearRect(0, 0, c.width, c.height);
    this.padDirty = false;
  }

  applyDraw(): void {
    const c = this.pad?.nativeElement;
    if (!c || !this.padDirty) return;
    this.setSignatureFromCanvas(c);
  }

  // ---- Type -------------------------------------------------------------

  applyTyped(): void {
    const text = this.typed().trim();
    if (!text) return;
    const canvas = document.createElement('canvas');
    canvas.width = 600;
    canvas.height = 180;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.fillStyle = '#12294d';
    ctx.textBaseline = 'middle';
    // Shrink the font until the name fits the canvas width.
    let size = 96;
    do {
      ctx.font = `${size}px 'Segoe Script', 'Brush Script MT', cursive`;
      size -= 4;
    } while (size > 24 && ctx.measureText(text).width > canvas.width - 40);
    ctx.fillText(text, 20, canvas.height / 2);
    this.setSignatureFromCanvas(canvas);
  }

  // ---- Upload -----------------------------------------------------------

  onUpload(ev: Event): void {
    const input = ev.target as HTMLInputElement;
    const f = input.files && input.files[0];
    if (!f) return;
    const url = URL.createObjectURL(f);
    this.setSignature(f, url);
  }

  // ---- Signature helpers ------------------------------------------------

  private setSignatureFromCanvas(canvas: HTMLCanvasElement): void {
    canvas.toBlob((blob) => {
      if (!blob) return;
      const file = new File([blob], 'signature.png', { type: 'image/png' });
      this.setSignature(file, canvas.toDataURL('image/png'));
    }, 'image/png');
  }

  private setSignature(file: File, url: string): void {
    const prev = this.signature();
    if (prev && prev.url.startsWith('blob:')) URL.revokeObjectURL(prev.url);
    this.signature.set({ file, url });
    // Decode the image to learn its aspect ratio, then constrain the placement
    // boxes to it. Reset first so a stale aspect never applies to the new image.
    this.sigAspect.set(null);
    const img = new Image();
    img.onload = () => {
      if (this.signature()?.url !== url) return; // superseded by a newer signature
      if (img.naturalWidth > 0 && img.naturalHeight > 0) {
        this.sigAspect.set(img.naturalWidth / img.naturalHeight);
        this.resnapAll();
      }
    };
    img.src = url;
  }

  submit(): void {
    const f = this.file();
    const sig = this.signature();
    if (!f || !sig || !this.regions().length) return;
    // Every drawn box is a placement of the single uploaded signature (imageIndex 0).
    const placements = this.regions().map((r) => ({
      imageIndex: 0,
      pageIndex: r.pageIndex,
      x: r.x,
      y: r.y,
      width: r.width,
      height: r.height,
    }));
    const fd = new FormData();
    fd.append('file', f, f.name);
    fd.append('signatures', sig.file, sig.file.name);
    fd.append('placements', JSON.stringify(placements));
    fd.append('flatten', String(this.flatten()));
    this.state.run(this.api.sign(fd));
  }
}
