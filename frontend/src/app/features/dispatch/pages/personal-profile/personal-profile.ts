import {DatePipe} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {Component, computed, inject, signal} from '@angular/core';
import {toSignal} from '@angular/core/rxjs-interop';
import {FormControl, NonNullableFormBuilder, ReactiveFormsModule, Validators} from '@angular/forms';
import {MatButtonModule} from '@angular/material/button';
import {MatFormFieldModule} from '@angular/material/form-field';
import {MatIconModule} from '@angular/material/icon';
import {MatInputModule} from '@angular/material/input';
import {AuthService} from '../../../../core/auth/auth.service';
import {AdminProfileDto, AiApiKeyStatusDto} from '../../../../core/services/dispatch-api.models';
import {DispatchApiService} from '../../../../core/services/dispatch-api.service';

@Component({
  selector: 'app-personal-profile',
  imports: [
    DatePipe,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    ReactiveFormsModule,
  ],
  templateUrl: './personal-profile.html',
  styleUrl: './personal-profile.scss',
})
export class PersonalProfile {
  private readonly api = inject(DispatchApiService);
  private readonly auth = inject(AuthService);
  private readonly formBuilder = inject(NonNullableFormBuilder);

  protected readonly status = signal<AiApiKeyStatusDto | null>(null);
  protected readonly isLoading = signal(true);
  protected readonly isSaving = signal(false);
  protected readonly isRemoving = signal(false);
  protected readonly hideKey = signal(true);
  protected readonly feedback = signal('');
  protected readonly errorMessage = signal('');
  protected readonly profileFeedback = signal('');
  protected readonly profileError = signal('');
  protected readonly isProfileLoading = signal(true);
  protected readonly isProfileSaving = signal(false);
  /** 資料庫裡目前的資料；手機有沒有改是跟它比，不是跟 Token 比 */
  private readonly savedProfile = signal<AdminProfileDto | null>(null);
  // 規則跟後端 AdminProfileUpdateDTO 一致：忘記密碼限定 10 字元並逐字比對
  protected readonly profileForm = this.formBuilder.group({
    account: this.formBuilder.control({value: '', disabled: true}, [Validators.required]),
    name: this.formBuilder.control('', [Validators.required, Validators.maxLength(60)]),
    phone: this.formBuilder.control('', [Validators.required, Validators.pattern(/^\s*09\d{8}\s*$/)]),
    currentPassword: this.formBuilder.control(''),
  });
  private readonly phoneValue = toSignal(this.profileForm.controls.phone.valueChanges, {initialValue: ''});
  /** 手機有變才要輸入目前的密碼：手機是忘記密碼的驗證依據，Token 被偷也換不掉 */
  protected readonly phoneChanged = computed(() => {
    const saved = this.savedProfile();
    return saved !== null && this.phoneValue().trim() !== (saved.phone ?? '');
  });
  protected readonly apiKeyControl = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, Validators.minLength(8), Validators.maxLength(200)],
  });

  constructor() {
    const user = this.auth.user();
    this.profileForm.patchValue({
      account: user?.account ?? '',
      name: user?.displayName ?? '',
    });
    this.loadProfile();
    this.loadStatus();
  }

  protected saveProfile(): void {
    const password = this.profileForm.controls.currentPassword.value;
    if (this.phoneChanged() && !password) {
      // 密碼欄只在改手機時出現，不掛固定的 required；手動標錯誤，mat-error 才會顯示，一輸入就會清掉
      this.profileForm.controls.currentPassword.setErrors({required: true});
    }
    if (this.profileForm.invalid) {
      this.profileForm.markAllAsTouched();
      return;
    }
    if (this.isProfileSaving() || this.isProfileLoading()) {
      return;
    }

    const before = this.savedProfile();
    const {name, phone} = this.profileForm.getRawValue();
    this.profileFeedback.set('');
    this.profileError.set('');
    this.isProfileSaving.set(true);
    this.api.updateAdminProfile({
      name: name.trim(),
      phone: phone.trim(),
      currentPassword: this.phoneChanged() ? password : undefined,
    }).subscribe({
      next: (profile) => {
        this.applyProfile(profile);
        // 姓名在登入 Token 裡，右上角和結案的處理人都讀 Token，重新登入才會換；手機不在 Token 裡，馬上生效
        this.profileFeedback.set(before !== null && before.name !== profile.name
          ? '已儲存。姓名要重新登入後，右上角和處理紀錄才會換成新的。'
          : '已儲存。');
        this.isProfileSaving.set(false);
      },
      error: (error: HttpErrorResponse) => {
        this.profileError.set(error.error?.message ?? '個人資料沒有儲存成功，請稍後再試。');
        this.isProfileSaving.set(false);
      },
    });
  }

  private loadProfile(): void {
    this.isProfileLoading.set(true);
    this.api.getAdminProfile().subscribe({
      next: (profile) => {
        this.applyProfile(profile);
        this.isProfileLoading.set(false);
      },
      error: () => {
        this.profileError.set('目前無法取得個人資料。');
        this.isProfileLoading.set(false);
      },
    });
  }

  private applyProfile(profile: AdminProfileDto): void {
    this.savedProfile.set(profile);
    this.profileForm.reset({
      account: profile.account,
      name: profile.name,
      phone: profile.phone ?? '',
      currentPassword: '',
    });
  }

  protected toggleKeyVisibility(): void {
    this.hideKey.update((hidden) => !hidden);
  }

  protected saveApiKey(): void {
    if (this.apiKeyControl.invalid || this.isSaving()) {
      this.apiKeyControl.markAsTouched();
      return;
    }

    this.feedback.set('');
    this.errorMessage.set('');
    this.isSaving.set(true);
    this.api.saveAiApiKey({apiKey: this.apiKeyControl.value.trim()}).subscribe({
      next: (status) => {
        this.status.set(status);
        this.apiKeyControl.reset();
        this.hideKey.set(true);
        this.feedback.set('AI API Key 已儲存。');
        this.isSaving.set(false);
      },
      error: (error: HttpErrorResponse) => {
        this.errorMessage.set(error.error?.message ?? '無法儲存 AI API Key，請稍後再試。');
        this.isSaving.set(false);
      },
    });
  }

  protected removeApiKey(): void {
    if (this.isRemoving()) {
      return;
    }

    this.feedback.set('');
    this.errorMessage.set('');
    this.isRemoving.set(true);
    this.api.removeAiApiKey().subscribe({
      next: () => {
        this.status.set({configured: false, maskedKey: null, updatedAt: null});
        this.feedback.set('AI API Key 已移除。');
        this.isRemoving.set(false);
      },
      error: (error: HttpErrorResponse) => {
        this.errorMessage.set(error.error?.message ?? '無法移除 AI API Key，請稍後再試。');
        this.isRemoving.set(false);
      },
    });
  }

  private loadStatus(): void {
    this.isLoading.set(true);
    this.api.getAiApiKeyStatus().subscribe({
      next: (status) => {
        this.status.set(status);
        this.isLoading.set(false);
      },
      error: () => {
        this.errorMessage.set('目前無法取得 AI API Key 設定狀態。');
        this.isLoading.set(false);
      },
    });
  }
}
