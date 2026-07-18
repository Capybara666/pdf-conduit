import { Component, EventEmitter, Input, Output } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';

import { CanvasNode } from '../../../core/pipeline.models';

/**
 * Parameter form for the currently-selected canvas node. Each kind renders only
 * its relevant fields; edits are emitted as `{key, value}` patches that the
 * canvas merges back into the node. SOURCE offers per-node file inclusion drawn
 * from the uploaded-file pool (add/remove). No output-destination picker — the
 * web pipeline always streams a ZIP of terminal outputs.
 */
@Component({
  selector: 'app-pipeline-inspector',
  standalone: true,
  imports: [TranslocoModule],
  template: `
    @if (!node) {
      <p class="hint-note">{{ 'pipeline.canvas.inspectorEmpty' | transloco }}</p>
    } @else {
      <div class="ins-head">
        <span class="badge op">{{ node.kind }}</span>
        <span class="node-id">{{ node.id }}</span>
      </div>

      @switch (node.kind) {
        @case ('SOURCE') {
          <div class="field">
            <span class="field-label">{{ 'pipeline.canvas.sourceFiles' | transloco }}</span>
            @if (pool.length) {
              <div class="src-list">
                @for (name of pool; track name) {
                  <label class="check">
                    <input
                      type="checkbox"
                      [checked]="node.files.includes(name)"
                      (change)="toggleFile(name, $any($event.target).checked)"
                    />
                    {{ name }}
                  </label>
                }
              </div>
            } @else {
              <p class="hint-note">{{ 'pipeline.canvas.uploadFirst' | transloco }}</p>
            }
          </div>
        }
        @case ('EXTRACT') {
          <div class="field">
            <label>{{ 'pages.pipeline.fieldPages' | transloco }}</label>
            <input type="text" [value]="node.pages" (input)="emit('pages', $any($event.target).value)" placeholder="1,3,5-8" />
          </div>
          <div class="field">
            <label>{{ 'pages.pipeline.fieldMode' | transloco }}</label>
            <select [value]="node.splitMode" (change)="emit('splitMode', $any($event.target).value)">
              <option value="COMBINE">{{ 'pages.pipeline.modeCombine' | transloco }}</option>
              <option value="SEPARATE">{{ 'pages.pipeline.modeSeparate' | transloco }}</option>
            </select>
          </div>
        }
        @case ('ROTATE') {
          <div class="field">
            <label>{{ 'pages.pipeline.fieldPages' | transloco }}</label>
            <input type="text" [value]="node.pages" (input)="emit('pages', $any($event.target).value)" placeholder="1,3,5-8" />
          </div>
          <div class="field">
            <label>{{ 'pages.pipeline.fieldAngle' | transloco }}</label>
            <select [value]="node.angle" (change)="emit('angle', +$any($event.target).value)">
              <option [value]="90">90°</option>
              <option [value]="180">180°</option>
              <option [value]="270">270°</option>
            </select>
          </div>
        }
        @case ('ARRANGE') {
          <div class="field">
            <label>{{ 'pages.pipeline.fieldOrder' | transloco }}</label>
            <input type="text" [value]="node.order" (input)="emit('order', $any($event.target).value)" placeholder="3,1,2" />
          </div>
        }
        @case ('COMPRESS') {
          <div class="field">
            <label>{{ 'pages.pipeline.fieldTarget' | transloco }}</label>
            <input type="text" [value]="node.targetSize" (input)="emit('targetSize', $any($event.target).value)" placeholder="5MB" />
          </div>
        }
        @case ('IMAGES_TO_PDF') {
          <div class="field">
            <label>{{ 'pages.pipeline.fieldPageSize' | transloco }}</label>
            <select [value]="node.pageSize" (change)="emit('pageSize', $any($event.target).value)">
              <option value="FIT">{{ 'pages.pipeline.sizeFit' | transloco }}</option>
              <option value="A4">A4</option>
              <option value="A3">A3</option>
              <option value="LETTER">Letter</option>
            </select>
          </div>
        }
        @case ('PROTECT') {
          <div class="field">
            <label>{{ 'pages.pipeline.fieldUserPassword' | transloco }}</label>
            <input type="password" [value]="node.password" (input)="emit('password', $any($event.target).value)" />
          </div>
          <div class="field">
            <label>{{ 'pages.pipeline.fieldOwnerPassword' | transloco }}</label>
            <input type="password" [value]="node.ownerPassword" (input)="emit('ownerPassword', $any($event.target).value)" />
          </div>
        }
        @case ('UNLOCK') {
          <div class="field">
            <label>{{ 'pages.pipeline.fieldPassword' | transloco }}</label>
            <input type="password" [value]="node.password" (input)="emit('password', $any($event.target).value)" />
          </div>
        }
        @case ('METADATA') {
          <div class="field"><label>{{ 'pages.pipeline.fieldTitle' | transloco }}</label><input type="text" [value]="node.metaTitle" (input)="emit('metaTitle', $any($event.target).value)" /></div>
          <div class="field"><label>{{ 'pages.pipeline.fieldAuthor' | transloco }}</label><input type="text" [value]="node.metaAuthor" (input)="emit('metaAuthor', $any($event.target).value)" /></div>
          <div class="field"><label>{{ 'pages.pipeline.fieldSubject' | transloco }}</label><input type="text" [value]="node.metaSubject" (input)="emit('metaSubject', $any($event.target).value)" /></div>
          <div class="field"><label>{{ 'pages.pipeline.fieldKeywords' | transloco }}</label><input type="text" [value]="node.metaKeywords" (input)="emit('metaKeywords', $any($event.target).value)" /></div>
          <label class="check"><input type="checkbox" [checked]="node.metaStrip" (change)="emit('metaStrip', $any($event.target).checked)" /> {{ 'pages.pipeline.stripMetadata' | transloco }}</label>
        }
        @case ('WATERMARK') {
          <div class="field">
            <label>{{ 'pages.pipeline.fieldText' | transloco }}</label>
            <input type="text" [value]="node.wmText" [disabled]="!!node.wmImageName" (input)="emit('wmText', $any($event.target).value)" placeholder="CONFIDENTIAL" />
          </div>
          <div class="field">
            <span class="field-label">{{ 'pipeline.canvas.wmImageLabel' | transloco }}</span>
            @if (node.wmImageName) {
              <div class="wm-asset">
                <span class="wm-name">{{ node.wmImageName }}</span>
                <button type="button" class="btn btn-ghost btn-xs" (click)="clearWmImage(fileInput)">{{ 'pipeline.canvas.wmImageClear' | transloco }}</button>
              </div>
            }
            <input #fileInput type="file" accept="image/*" (change)="onWmImage(fileInput)" />
            <p class="hint-note">{{ 'pipeline.canvas.wmImageHint' | transloco }}</p>
          </div>
          <div class="field"><label>{{ 'pages.pipeline.fieldOpacity' | transloco }}</label><input type="number" min="0.05" max="1" step="0.05" [value]="node.wmOpacity" (input)="emit('wmOpacity', +$any($event.target).value)" /></div>
          <div class="field"><label>{{ 'pages.pipeline.fieldRotation' | transloco }}</label><input type="number" min="0" max="360" step="5" [value]="node.wmRotation" (input)="emit('wmRotation', +$any($event.target).value)" /></div>
          <div class="field"><label>{{ 'pages.pipeline.fieldScale' | transloco }}</label><input type="number" min="0.1" max="1" step="0.05" [value]="node.wmScale" (input)="emit('wmScale', +$any($event.target).value)" /></div>
        }
        @case ('TO_IMAGES') {
          <div class="field">
            <label>{{ 'pages.pipeline.fieldFormat' | transloco }}</label>
            <select [value]="node.imageFormat" (change)="emit('imageFormat', $any($event.target).value)">
              <option value="PNG">PNG</option>
              <option value="JPEG">JPG</option>
            </select>
          </div>
          <div class="field"><label>{{ 'pages.pipeline.fieldDpi' | transloco }}</label><input type="number" min="36" max="600" [value]="node.imageDpi" (input)="emit('imageDpi', +$any($event.target).value)" /></div>
        }
        @case ('TO_TEXT') {
          <div class="field">
            <label>{{ 'pages.pipeline.fieldFormat' | transloco }}</label>
            <select [value]="node.textFormat" (change)="emit('textFormat', $any($event.target).value)">
              <option value="TXT">TXT</option>
            </select>
          </div>
        }
        @default {
          <p class="hint-note">{{ 'pipeline.canvas.noParams' | transloco }}</p>
        }
      }
    }
  `,
  styles: [
    `
      :host {
        display: flex;
        flex-direction: column;
        gap: 0.85rem;
      }
      .ins-head {
        display: flex;
        align-items: center;
        gap: 0.5rem;
      }
      .badge {
        font-size: 0.7rem;
        font-weight: 700;
        letter-spacing: 0.03em;
        padding: 0.15rem 0.45rem;
        border-radius: 5px;
        background: var(--accent-soft);
        color: var(--accent);
      }
      .node-id {
        font-size: 0.8rem;
        color: var(--text-muted);
      }
      .src-list {
        display: flex;
        flex-direction: column;
        gap: 0.35rem;
        max-height: 220px;
        overflow: auto;
      }
      .wm-asset {
        display: flex;
        align-items: center;
        gap: 0.5rem;
      }
      .wm-name {
        font-size: 0.82rem;
        word-break: break-all;
        color: var(--text-muted);
      }
      .btn-xs {
        padding: 0.15rem 0.45rem;
        font-size: 0.72rem;
      }
    `,
  ],
})
export class PipelineInspectorComponent {
  @Input() node: CanvasNode | null = null;
  /** Uploaded file basenames available to include in a SOURCE node. */
  @Input() pool: string[] = [];

  @Output() patch = new EventEmitter<Partial<CanvasNode>>();
  /** Chosen watermark image (or null to clear) for the selected WATERMARK node. */
  @Output() asset = new EventEmitter<File | null>();

  emit<K extends keyof CanvasNode>(key: K, value: CanvasNode[K]): void {
    this.patch.emit({ [key]: value } as Partial<CanvasNode>);
  }

  toggleFile(name: string, on: boolean): void {
    if (!this.node) return;
    const files = on
      ? [...this.node.files, name]
      : this.node.files.filter((f) => f !== name);
    this.patch.emit({ files });
  }

  onWmImage(input: HTMLInputElement): void {
    const file = input.files?.[0] ?? null;
    if (file) this.asset.emit(file);
  }

  clearWmImage(input: HTMLInputElement): void {
    input.value = '';
    this.asset.emit(null);
  }
}
