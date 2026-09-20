import {Component, inject, signal} from '@angular/core';
import {DatePipe} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
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
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    ReactiveFormsModule,
    DatePipe
  ],
  templateUrl: './personal-profile.html',
  styleUrl: './personal-profile.scss',
})
export class PersonalProfile {
  private readonly api = inject(DispatchApiService);

  //後端回傳的金鑰狀態；只有遮罩後的末 4 碼，拿不到完整金鑰
  protected readonly status = signal<AiApiKeyStatusDto | null>(null);
  protected readonly errorMessage = signal('');

  hide = signal(true);


  protected readonly apiKeyControl = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, Validators.minLength(8), Validators.maxLength(200)],
  });

  constructor() {
    this.loadStatus();
  }

  clickEvent(event: MouseEvent) {
    this.hide.set(!this.hide());
    event.stopPropagation();
  }

   //新增與更新共用同一支 PUT：一人只有一把金鑰，兩者都是覆蓋舊的
  protected saveApiKey(): void {
    if (this.apiKeyControl.invalid || this.apiKeyControl.disabled) {
      this.apiKeyControl.markAsTouched();
      return;
    }

    this.errorMessage.set('');
    // 送出期間鎖住欄位，避免連點送出兩次
    this.apiKeyControl.disable();
    this.api.saveAiApiKey({apiKey: this.apiKeyControl.value.trim()}).subscribe({
      next: (status) => {
        // 狀態一律以後端回傳的為準，末 4 碼怎麼算不在前端再寫一份
        this.status.set(status);
        this.apiKeyControl.reset();
        this.apiKeyControl.enable();
        this.hide.set(true);
      },
      error: (error: HttpErrorResponse) => {
        this.errorMessage.set(error.error?.message ?? '儲存失敗，請稍後再試');
        this.apiKeyControl.enable();
      },
    });
  }

  //移除後 AI 助理會擋下對話，提示回來設定金鑰
  protected removeApiKey(): void {
    this.errorMessage.set('');
    this.api.removeAiApiKey().subscribe({
      next: () => this.loadStatus(),
      error: (error: HttpErrorResponse) =>
        this.errorMessage.set(error.error?.message ?? '移除失敗，請稍後再試'),
    });
  }

  private loadStatus(): void {
    this.api.getAiApiKeyStatus().subscribe({
      next: (status) => this.status.set(status),
      error: () => this.errorMessage.set('讀取設定狀態失敗'),
    });
  }
}
