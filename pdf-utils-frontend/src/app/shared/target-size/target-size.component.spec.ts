import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl } from '@angular/forms';
import { By } from '@angular/platform-browser';

import {
  TargetSizeComponent,
  TargetUnit,
  UNIT_BYTES,
  composeTargetSize,
} from './target-size.component';
import { TRANSLOCO_TESTING_PROVIDERS, translocoTesting } from '../../testing/transloco-testing';

/** A page-like host that owns the controls, exactly as the real callers do. */
@Component({
  standalone: true,
  imports: [TargetSizeComponent],
  template: `
    <app-target-size [amount]="amount" [unit]="unit" inputId="host-amount" placeholder="e.g. 5" />
  `,
})
class HostComponent {
  readonly amount = new FormControl<number | null>(5);
  readonly unit = new FormControl<TargetUnit>('MB', { nonNullable: true });
}

/**
 * The shared amount + unit picker. Two things matter here: the string handed to
 * the backend must be exactly `<amount><unit>` with no separator, and the
 * component must bind the CALLER's controls rather than copies of them — pages
 * derive warnings and persistence from those same controls, so a one-way copy
 * would silently desynchronise what the user sees from what gets submitted.
 */
describe('TargetSizeComponent', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;

  it('composeTargetSize joins amount and unit with no separator', () => {
    expect(composeTargetSize(5, 'MB')).toBe('5MB');
    expect(composeTargetSize(800, 'KB')).toBe('800KB');
    expect(composeTargetSize(1.5, 'GB')).toBe('1.5GB');
  });

  it('UNIT_BYTES uses binary multipliers', () => {
    expect(UNIT_BYTES.KB).toBe(1024);
    expect(UNIT_BYTES.MB).toBe(1024 * 1024);
    expect(UNIT_BYTES.GB).toBe(1024 * 1024 * 1024);
  });

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HostComponent, translocoTesting()],
      providers: [TRANSLOCO_TESTING_PROVIDERS],
    });
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    fixture.detectChanges();
  });

  function input(): HTMLInputElement {
    return fixture.debugElement.query(By.css('input[type="number"]')).nativeElement;
  }

  function select(): HTMLSelectElement {
    return fixture.debugElement.query(By.css('select.unit')).nativeElement;
  }

  it('renders the caller-supplied control values', () => {
    expect(input().value).toBe('5');
    expect(select().value).toBe('MB');
  });

  it('applies the caller id and placeholder so an external <label for> works', () => {
    expect(input().id).toBe('host-amount');
    expect(input().placeholder).toBe('e.g. 5');
  });

  it('offers exactly the KB/MB/GB units', () => {
    const options = Array.from(select().options).map((o) => o.value);
    expect(options).toEqual(['KB', 'MB', 'GB']);
  });

  it('labels the unit picker for screen readers', () => {
    expect(select().getAttribute('aria-label')).toBeTruthy();
  });

  it('writes user edits back into the caller controls', () => {
    input().value = '800';
    input().dispatchEvent(new Event('input'));
    select().value = 'KB';
    select().dispatchEvent(new Event('change'));

    expect(host.amount.value).toBe(800);
    expect(host.unit.value).toBe('KB');
    expect(composeTargetSize(host.amount.value, host.unit.value)).toBe('800KB');
  });

  it('reflects programmatic control changes back into the DOM', () => {
    host.amount.setValue(2);
    host.unit.setValue('GB');
    fixture.detectChanges();

    expect(input().value).toBe('2');
    expect(select().value).toBe('GB');
  });
});
