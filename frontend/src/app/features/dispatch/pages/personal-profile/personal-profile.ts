import {DatePipe} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {Component, inject, signal} from '@angular/core';
import {FormControl, ReactiveFormsModule, Validators} from '@angular/forms';
import {MatButtonModule} from '@angular/material/button';
import {MatFormFieldModule} from '@angular/material/form-field';
import {MatIconModule} from '@angular/material/icon';
import {MatInputModule} from '@angular/material/input';
import {AiApiKeyStatusDto} from '../../../../core/services/dispatch-api.models';
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

  protected readonly status = signal<AiApiKeyStatusDto | null>(null);
  protected readonly isLoading = signal(true);
  protected readonly isSaving = signal(false);
  protected readonly isRemoving = signal(false);
  protected readonly hideKey = signal(true);
  protected readonly feedback = signal('');
  protected readonly errorMessage = signal('');
  protected readonly apiKeyControl = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, Validators.minLength(8), Validators.maxLength(200)],
  });

  constructor() {
    this.loadStatus();
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
