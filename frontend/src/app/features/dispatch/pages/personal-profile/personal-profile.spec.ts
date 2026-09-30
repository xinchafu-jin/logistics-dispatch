import { ANIMATION_MODULE_TYPE } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { vi } from 'vitest';

import { PersonalProfile } from './personal-profile';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import { AdminProfileDto } from '../../../../core/services/dispatch-api.models';

/** 資料庫裡目前是「系統管理員」、0912345678；改手機要附目前的密碼，只改姓名不用 */
describe('PersonalProfile', () => {
  let fixture: ComponentFixture<PersonalProfile>;
  let updateAdminProfile: ReturnType<typeof vi.fn>;
  const saved: AdminProfileDto = { account: 'admin', name: '系統管理員', phone: '0912345678' };

  beforeEach(async () => {
    updateAdminProfile = vi.fn((request: { name: string; phone: string }) =>
      of({ account: 'admin', name: request.name, phone: request.phone }));
    await TestBed.configureTestingModule({
      imports: [PersonalProfile],
      providers: [
        { provide: ANIMATION_MODULE_TYPE, useValue: 'NoopAnimations' },
        {
          provide: DispatchApiService,
          useValue: {
            getAdminProfile: () => of(saved),
            updateAdminProfile,
            getAiApiKeyStatus: () => of({ configured: false, maskedKey: null, updatedAt: null }),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PersonalProfile);
    await fixture.whenStable();
    fixture.detectChanges();
  });

  // profileForm 是 protected；中括號存取是 TypeScript 留給測試的出口
  function form() {
    return fixture.componentInstance['profileForm'];
  }

  function submit(): void {
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.profile-form button[type="submit"]')!.click();
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('載入時以資料庫的姓名與手機為準，密碼欄不出現', () => {
    const inputs = (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLInputElement>('.profile-form input');
    expect(inputs[1].value).toBe('系統管理員');
    expect(inputs[2].value).toBe('0912345678');
    expect(text()).not.toContain('目前的密碼');
  });

  it('只改姓名：不帶密碼送出，並提示要重新登入', () => {
    form().controls.name.setValue('王主管');
    submit();

    expect(updateAdminProfile).toHaveBeenCalledWith({ name: '王主管', phone: '0912345678', currentPassword: undefined });
    expect(text()).toContain('重新登入');
  });

  it('改手機沒填密碼：不送出，顯示要輸入密碼', () => {
    form().controls.phone.setValue('0987654321');
    fixture.detectChanges();
    expect(text()).toContain('目前的密碼');

    submit();

    expect(updateAdminProfile).not.toHaveBeenCalled();
    expect(text()).toContain('修改手機號碼需要輸入目前的密碼');
  });

  it('改手機有填密碼：帶密碼送出，只改手機不提示重新登入', () => {
    form().controls.phone.setValue('0987654321');
    form().controls.currentPassword.setValue('correct-pw');
    submit();

    expect(updateAdminProfile).toHaveBeenCalledWith({ name: '系統管理員', phone: '0987654321', currentPassword: 'correct-pw' });
    expect(text()).toContain('已儲存');
    expect(text()).not.toContain('重新登入');
  });

  it('手機 11 碼：擋在前端，不送出', () => {
    form().controls.phone.setValue('09111111111');
    form().controls.currentPassword.setValue('correct-pw');
    submit();

    expect(updateAdminProfile).not.toHaveBeenCalled();
    expect(text()).toContain('09 開頭的 10 碼');
  });
});
