import { Routes } from '@angular/router';

/**
 * Route table. Each operation lazy-loads its standalone page component. Keep the
 * path segments in sync with `NAV_ITEMS` ids in core/operations.ts.
 *
 * Tab titles are localized: each route declares a Transloco `data.titleKey`
 * (usually an `op.<id>.label` / `nav.*` key) which `TranslatedTitleStrategy`
 * turns into "<label> · PDF Conduit" and re-applies on language change. The
 * landing page has no key, so it shows the bare brand.
 */
export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    loadComponent: () => import('./pages/home/home.page').then((m) => m.HomePage),
  },
  {
    path: 'privacy',
    data: { titleKey: 'privacy.title' },
    loadComponent: () => import('./pages/privacy/privacy.page').then((m) => m.PrivacyPage),
  },
  {
    path: 'merge',
    data: { titleKey: 'op.merge.label' },
    loadComponent: () => import('./pages/merge/merge.page').then((m) => m.MergePage),
  },
  {
    path: 'extract',
    data: { titleKey: 'op.extract.label' },
    loadComponent: () => import('./pages/extract/extract.page').then((m) => m.ExtractPage),
  },
  {
    path: 'compress',
    data: { titleKey: 'op.compress.label' },
    loadComponent: () => import('./pages/compress/compress.page').then((m) => m.CompressPage),
  },
  {
    path: 'rotate',
    data: { titleKey: 'op.rotate.label' },
    loadComponent: () => import('./pages/rotate/rotate.page').then((m) => m.RotatePage),
  },
  {
    path: 'arrange',
    data: { titleKey: 'op.arrange.label' },
    loadComponent: () => import('./pages/arrange/arrange.page').then((m) => m.ArrangePage),
  },
  {
    path: 'nup',
    data: { titleKey: 'op.nup.label' },
    loadComponent: () => import('./pages/nup/nup.page').then((m) => m.NupPage),
  },
  {
    path: 'to-pdf',
    data: { titleKey: 'op.to-pdf.label' },
    loadComponent: () => import('./pages/to-pdf/to-pdf.page').then((m) => m.ToPdfPage),
  },
  {
    path: 'protect',
    data: { titleKey: 'op.protect.label' },
    loadComponent: () => import('./pages/protect/protect.page').then((m) => m.ProtectPage),
  },
  {
    path: 'unlock',
    data: { titleKey: 'op.unlock.label' },
    loadComponent: () => import('./pages/unlock/unlock.page').then((m) => m.UnlockPage),
  },
  {
    path: 'metadata',
    data: { titleKey: 'op.metadata.label' },
    loadComponent: () => import('./pages/metadata/metadata.page').then((m) => m.MetadataPage),
  },
  {
    path: 'watermark',
    data: { titleKey: 'op.watermark.label' },
    loadComponent: () => import('./pages/watermark/watermark.page').then((m) => m.WatermarkPage),
  },
  {
    path: 'redact',
    data: { titleKey: 'op.redact.label' },
    loadComponent: () => import('./pages/redact/redact.page').then((m) => m.RedactPage),
  },
  {
    path: 'crop',
    data: { titleKey: 'op.crop.label' },
    loadComponent: () => import('./pages/crop/crop.page').then((m) => m.CropPage),
  },
  {
    path: 'to-images',
    data: { titleKey: 'op.to-images.label' },
    loadComponent: () => import('./pages/to-images/to-images.page').then((m) => m.ToImagesPage),
  },
  {
    path: 'gdpr-scan',
    data: { titleKey: 'op.gdpr-scan.label' },
    loadComponent: () => import('./pages/gdpr-scan/gdpr-scan.page').then((m) => m.GdprScanPage),
  },
  {
    path: 'to-text',
    data: { titleKey: 'op.to-text.label' },
    loadComponent: () => import('./pages/to-text/to-text.page').then((m) => m.ToTextPage),
  },
  {
    path: 'wizard',
    data: { titleKey: 'op.wizard.label' },
    loadComponent: () => import('./pages/wizard/wizard.page').then((m) => m.WizardPage),
  },
  {
    path: 'pipeline',
    data: { titleKey: 'op.pipeline.label' },
    loadComponent: () => import('./pages/pipeline/pipeline.page').then((m) => m.PipelinePage),
  },
  { path: '**', redirectTo: '' },
];
