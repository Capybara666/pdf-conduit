import { Routes } from '@angular/router';

/**
 * Route table. Each operation lazy-loads its standalone page component (scaffold
 * placeholders for now). Keep the path segments in sync with `NAV_ITEMS` ids in
 * core/operations.ts.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'merge' },
  {
    path: 'merge',
    title: 'Merge · PDF Conduit',
    loadComponent: () => import('./pages/merge/merge.page').then((m) => m.MergePage),
  },
  {
    path: 'extract',
    title: 'Extract · PDF Conduit',
    loadComponent: () => import('./pages/extract/extract.page').then((m) => m.ExtractPage),
  },
  {
    path: 'compress',
    title: 'Compress · PDF Conduit',
    loadComponent: () => import('./pages/compress/compress.page').then((m) => m.CompressPage),
  },
  {
    path: 'rotate',
    title: 'Rotate · PDF Conduit',
    loadComponent: () => import('./pages/rotate/rotate.page').then((m) => m.RotatePage),
  },
  {
    path: 'arrange',
    title: 'Arrange · PDF Conduit',
    loadComponent: () => import('./pages/arrange/arrange.page').then((m) => m.ArrangePage),
  },
  {
    path: 'to-pdf',
    title: 'To PDF · PDF Conduit',
    loadComponent: () => import('./pages/to-pdf/to-pdf.page').then((m) => m.ToPdfPage),
  },
  {
    path: 'protect',
    title: 'Protect · PDF Conduit',
    loadComponent: () => import('./pages/protect/protect.page').then((m) => m.ProtectPage),
  },
  {
    path: 'unlock',
    title: 'Unlock · PDF Conduit',
    loadComponent: () => import('./pages/unlock/unlock.page').then((m) => m.UnlockPage),
  },
  {
    path: 'metadata',
    title: 'Metadata · PDF Conduit',
    loadComponent: () => import('./pages/metadata/metadata.page').then((m) => m.MetadataPage),
  },
  {
    path: 'watermark',
    title: 'Watermark · PDF Conduit',
    loadComponent: () => import('./pages/watermark/watermark.page').then((m) => m.WatermarkPage),
  },
  {
    path: 'redact',
    title: 'Redact · PDF Conduit',
    loadComponent: () => import('./pages/redact/redact.page').then((m) => m.RedactPage),
  },
  {
    path: 'to-images',
    title: 'To Images · PDF Conduit',
    loadComponent: () => import('./pages/to-images/to-images.page').then((m) => m.ToImagesPage),
  },
  {
    path: 'to-text',
    title: 'To Text · PDF Conduit',
    loadComponent: () => import('./pages/to-text/to-text.page').then((m) => m.ToTextPage),
  },
  {
    path: 'wizard',
    title: 'Wizard · PDF Conduit',
    loadComponent: () => import('./pages/wizard/wizard.page').then((m) => m.WizardPage),
  },
  {
    path: 'pipeline',
    title: 'Pipeline · PDF Conduit',
    loadComponent: () => import('./pages/pipeline/pipeline.page').then((m) => m.PipelinePage),
  },
  { path: '**', redirectTo: 'merge' },
];
