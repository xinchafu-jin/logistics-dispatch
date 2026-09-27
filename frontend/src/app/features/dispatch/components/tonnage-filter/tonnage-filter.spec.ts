import { TestBed } from '@angular/core/testing';
import { ANIMATION_MODULE_TYPE, signal } from '@angular/core';
import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { TestKey } from '@angular/cdk/testing';
import { MatSelectHarness } from '@angular/material/select/testing';
import { AdminThemeService } from '../../../../core/theme/admin-theme.service';
import { TonnageFilter, TonnageSelection } from './tonnage-filter';

describe('TonnageFilter themed dropdown', () => {
  const lightTheme = signal(false);

  beforeEach(() => {
    lightTheme.set(false);
    TestBed.configureTestingModule({
      imports: [TonnageFilter],
      providers: [
        {provide: AdminThemeService, useValue: {isLightTheme: lightTheme}},
        {provide: ANIMATION_MODULE_TYPE, useValue: 'NoopAnimations'},
      ],
    });
  });

  async function render() {
    const fixture = TestBed.createComponent(TonnageFilter);
    fixture.componentRef.setInput('tonnages', [1.75, 3.5, 5, 8]);
    const changes: TonnageSelection[] = [];
    fixture.componentInstance.valueChange.subscribe(value => {
      changes.push(value); fixture.componentRef.setInput('value', value);
    });
    fixture.detectChanges();
    const select = await TestbedHarnessEnvironment.loader(fixture).getHarness(MatSelectHarness);
    return {fixture, select, changes};
  }

  it('shows the actual tonnages and emits numeric values rather than strings', async () => {
    const {fixture, select, changes} = await render();
    await select.open();
    expect(await Promise.all((await select.getOptions()).map(option => option.getText())))
      .toEqual(['全部噸位', '1.75 噸', '3.5 噸', '5 噸', '8 噸']);
    await select.clickOptions({text: '3.5 噸'});
    expect(changes).toEqual([3.5]);
    expect(await select.isOpen()).toBe(false);
    expect(await select.getValueText()).toContain('3.5 噸');
    await select.open(); await select.clickOptions({text: '全部噸位'});
    expect(changes).toEqual([3.5, 'all']);
    fixture.destroy();
  });

  it('gives the detached overlay the correct theme on every opening', async () => {
    const {fixture, select} = await render();
    await select.open();
    expect(document.querySelector('.tonnage-filter-panel-dark')).not.toBeNull();
    await select.close();
    lightTheme.set(true); fixture.detectChanges();
    await select.open();
    expect(document.querySelector('.tonnage-filter-panel-light')).not.toBeNull();
    expect(document.querySelector('.tonnage-filter-panel-dark')).toBeNull();
    await select.close(); fixture.destroy();
  });

  it('supports opening with the keyboard and cancelling without changing the filter', async () => {
    const {fixture, select, changes} = await render();
    const host = await select.host();
    await host.sendKeys(TestKey.ENTER);
    expect(await select.isOpen()).toBe(true);
    await host.sendKeys(TestKey.ESCAPE);
    expect(await select.isOpen()).toBe(false);
    expect(changes).toEqual([]);
    expect(await select.getValueText()).toContain('全部噸位');
    fixture.destroy();
  });
});
