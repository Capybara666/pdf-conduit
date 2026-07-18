import { Injectable, signal } from '@angular/core';

export type ToastKind = 'info' | 'success' | 'warning' | 'error';

export interface ToastAction {
  /** Button label. */
  label: string;
  /** Router link to navigate to when the action is clicked (e.g. ['/']). */
  link?: (string | number)[];
  /** Optional URL fragment for the link (e.g. 'pro'). */
  fragment?: string;
}

export interface Toast {
  id: number;
  kind: ToastKind;
  title: string;
  message?: string;
  action?: ToastAction;
  /** Auto-dismiss delay in ms; 0 = sticky. */
  duration: number;
}

/**
 * Minimal global toast/snackbar queue. Any component or service can push a
 * toast; `ToastContainerComponent` (mounted once in the app shell) renders
 * them. No external dependency.
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly toasts = signal<Toast[]>([]);
  private nextId = 1;

  show(toast: Omit<Toast, 'id' | 'duration'> & { duration?: number }): number {
    const id = this.nextId++;
    const duration = toast.duration ?? (toast.action || toast.kind === 'error' ? 8000 : 4500);
    this.toasts.update((list) => [...list, { ...toast, id, duration }]);
    if (duration > 0 && typeof window !== 'undefined') {
      window.setTimeout(() => this.dismiss(id), duration);
    }
    return id;
  }

  info(title: string, message?: string) {
    return this.show({ kind: 'info', title, message });
  }
  success(title: string, message?: string) {
    return this.show({ kind: 'success', title, message });
  }
  warning(title: string, message?: string) {
    return this.show({ kind: 'warning', title, message });
  }
  error(title: string, message?: string) {
    return this.show({ kind: 'error', title, message });
  }

  dismiss(id: number): void {
    this.toasts.update((list) => list.filter((t) => t.id !== id));
  }
}
