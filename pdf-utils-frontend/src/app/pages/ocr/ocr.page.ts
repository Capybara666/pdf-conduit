import { Component, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { TranslocoModule } from '@jsverse/transloco';

import { ApiService } from '../../core/api.service';
import { CapabilitiesService } from '../../core/capabilities.service';
import { OperationState } from '../../core/operation-state';
import { WorkStateService } from '../../core/work-state.service';
import { FileDropZoneComponent } from '../../shared/file-drop-zone/file-drop-zone.component';
import { PageHeaderComponent } from '../../shared/page-header/page-header.component';
import { ResultPanelComponent } from '../../shared/result-panel/result-panel.component';

/**
 * Native-name labels for the most common Tesseract language codes. Deliberately
 * NOT run through i18n: a language's own name is the clearest label in any UI
 * locale ("Polski" beats "Polish"/"Polnisch"). Codes without an entry are shown
 * raw (e.g. `chi_sim`).
 */
const LANG_LABELS: Record<string, string> = {
  eng: 'English',
  pol: 'Polski',
  deu: 'Deutsch',
  fra: 'Français',
  spa: 'Español',
};

/**
 * OCR a scanned / image-only PDF into a searchable PDF: the server renders each page, runs
 * Tesseract, and adds an invisible text layer so the visual page is unchanged but the text
 * becomes selectable and searchable. Single input → a single searchable PDF.
 *
 * Language selection: when the server advertises its installed Tesseract languages
 * (`GET /api/capabilities`), they are offered as checkboxes (joined `eng+pol` on submit) — no
 * language-code jargon needed. A collapsed "advanced" free-text field remains for custom
 * traineddata codes and overrides the checkboxes when filled. If the capability fetch failed or
 * returned nothing, the page falls back to the plain free-text input exactly as before.
 */
@Component({
  selector: 'app-ocr-page',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    TranslocoModule,
    FileDropZoneComponent,
    PageHeaderComponent,
    ResultPanelComponent,
  ],
  template: `
    <section class="op-page">
      <app-page-header
        [title]="'pages.ocr.title' | transloco"
        [description]="'pages.ocr.description' | transloco"
      />

      <app-file-drop-zone
        [multiple]="false"
        accept=".pdf,.png,.jpg,.jpeg,.tif,.tiff,.bmp,.gif,.webp"
        [hint]="'pages.ocr.hint' | transloco"
        (filesChange)="files.set($event)"
      />

      <div class="card form-grid">
        <div class="field">
          <label [attr.for]="langOptions().length ? null : 'ocr-langs'">
            {{ 'pages.ocr.languages' | transloco }}
          </label>

          @if (langOptions().length) {
            <div class="lang-grid" role="group" [attr.aria-label]="'pages.ocr.languages' | transloco">
              @for (code of langOptions(); track code) {
                <label class="lang-option">
                  <input
                    type="checkbox"
                    [checked]="selected().includes(code)"
                    (change)="toggle(code, $event)"
                  />
                  <span>{{ labelFor(code) }}</span>
                </label>
              }
            </div>
            @if (!selected().length && !languages.value.trim()) {
              <span class="help">{{ 'pages.ocr.langNone' | transloco }}</span>
            }
            <details class="lang-advanced">
              <summary>{{ 'pages.ocr.langAdvanced' | transloco }}</summary>
              <input
                id="ocr-langs"
                type="text"
                [formControl]="languages"
                [placeholder]="'pages.ocr.languagesPlaceholder' | transloco"
              />
              <span class="help">{{ 'pages.ocr.languagesHelp' | transloco }}</span>
            </details>
          } @else {
            <input
              id="ocr-langs"
              type="text"
              [formControl]="languages"
              [placeholder]="'pages.ocr.languagesPlaceholder' | transloco"
            />
            <span class="help">{{ 'pages.ocr.languagesHelp' | transloco }}</span>
          }
        </div>
      </div>

      <div class="btn-row">
        <button
          type="button"
          class="btn btn-primary"
          [disabled]="!files().length || state.loading()"
          (click)="submit()"
        >
          {{ 'pages.ocr.submit' | transloco }}
        </button>
        <button type="button" class="btn" (click)="clear()">{{ 'common.clear' | transloco }}</button>
      </div>

      <app-result-panel
        [loading]="state.loading()"
        [loadingLabel]="'pages.ocr.loading' | transloco"
        [error]="state.error()"
        [result]="state.result()"
        (retry)="submit()"
      />
    </section>
  `,
  styles: [
    `
      .lang-grid {
        display: flex;
        flex-wrap: wrap;
        gap: 0.4rem 1.1rem;
      }
      .lang-option {
        display: inline-flex;
        align-items: center;
        gap: 0.45rem;
        cursor: pointer;
        user-select: none;
      }
      .lang-option input {
        margin: 0;
        cursor: pointer;
      }
      .lang-advanced {
        margin-top: 0.5rem;
      }
      .lang-advanced summary {
        cursor: pointer;
        font-size: 0.85rem;
        opacity: 0.8;
      }
      .lang-advanced input {
        margin-top: 0.5rem;
        width: 100%;
      }
    `,
  ],
})
export class OcrPage {
  protected readonly files = signal<File[]>([]);
  /** Advanced free-text spec (custom traineddata); overrides the checkboxes when non-blank. */
  protected readonly languages = new FormControl('', { nonNullable: true });
  /** Checkbox selection, kept in the server catalog's order. */
  protected readonly selected = signal<string[]>([]);
  protected readonly state = new OperationState();

  private readonly workState = inject(WorkStateService);
  private readonly capabilities = inject(CapabilitiesService);

  /** Installed languages advertised by the server; empty → free-text fallback. */
  protected readonly langOptions = this.capabilities.ocrLanguages;

  constructor(private readonly api: ApiService) {
    this.workState.persist('ocr', { languages: this.languages, selected: this.selected });
  }

  protected labelFor(code: string): string {
    return LANG_LABELS[code] ?? code;
  }

  protected toggle(code: string, ev: Event): void {
    const checked = (ev.target as HTMLInputElement).checked;
    const set = new Set(this.selected());
    if (checked) {
      set.add(code);
    } else {
      set.delete(code);
    }
    // Normalise to the catalog's order so the submitted spec is stable.
    this.selected.set(this.langOptions().filter((c) => set.has(c)));
  }

  clear(): void {
    this.workState.reset('ocr');
    this.files.set([]);
    this.state.reset();
  }

  submit(): void {
    const file = this.files()[0];
    if (!file) return;
    const fd = new FormData();
    fd.append('file', file, file.name);
    const langs = this.effectiveLanguages();
    if (langs) fd.append('languages', langs);
    this.state.run(this.api.ocr(fd));
  }

  /** The `-l` spec to submit: advanced text wins; else checked codes joined with `+`; else server default. */
  private effectiveLanguages(): string {
    const advanced = this.languages.value.trim();
    if (advanced) return advanced;
    return this.selected().join('+');
  }
}
