import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { ApiError } from '../auth/auth.models';
import { API_BASE_URL, AuthService } from '../auth/auth.service';

interface Driver {
  id: number;
  account: string;
  name: string;
  phone: string | null;
  workStart: string;
  workEnd: string;
  restDuration: number;
  maxOvertimeMinutes: number | null;
  isActive: boolean;
}

@Component({
  selector: 'app-manager-dashboard',
  imports: [ReactiveFormsModule],
  templateUrl: './manager-dashboard.html',
  styleUrl: './manager-dashboard.css'
})
export class ManagerDashboard implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly formBuilder = inject(FormBuilder);
  protected readonly auth = inject(AuthService);

  protected readonly drivers = signal<Driver[]>([]);
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly errorMessage = signal('');
  protected readonly successMessage = signal('');
  protected readonly form = this.formBuilder.nonNullable.group({
    account: ['', [Validators.required, Validators.maxLength(50)]],
    password: ['', [Validators.required, Validators.minLength(8), Validators.maxLength(72)]],
    name: ['', [Validators.required, Validators.maxLength(30)]],
    phone: ['', Validators.maxLength(10)],
    workStart: ['08:00', Validators.required],
    workEnd: ['17:00', Validators.required],
    restDuration: [60, [Validators.required, Validators.min(0)]],
    maxOvertimeMinutes: [120, Validators.min(0)],
    isActive: [true, Validators.required]
  });

  ngOnInit(): void {
    this.loadDrivers();
  }

  protected loadDrivers(): void {
    this.loading.set(true);
    this.errorMessage.set('');
    this.http.get<Driver[]>(`${API_BASE_URL}/drivers`)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (drivers) => this.drivers.set(drivers),
        error: (error: HttpErrorResponse) => this.showError(error, '無法載入司機資料')
      });
  }

  protected createDriver(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }

    this.saving.set(true);
    this.errorMessage.set('');
    this.successMessage.set('');
    this.http.post<Driver>(`${API_BASE_URL}/drivers`, this.form.getRawValue())
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (driver) => {
          this.drivers.update((drivers) => [...drivers, driver]);
          this.successMessage.set(`已建立 ${driver.name} 的司機帳號`);
          this.form.reset({
            account: '', password: '', name: '', phone: '',
            workStart: '08:00', workEnd: '17:00', restDuration: 60,
            maxOvertimeMinutes: 120, isActive: true
          });
        },
        error: (error: HttpErrorResponse) => this.showError(error, '建立司機帳號失敗')
      });
  }

  protected toggleStatus(driver: Driver): void {
    this.errorMessage.set('');
    this.http.patch<Driver>(`${API_BASE_URL}/drivers/${driver.id}/status`, {
      isActive: !driver.isActive
    }).subscribe({
      next: (updated) => this.drivers.update((drivers) =>
        drivers.map((item) => item.id === updated.id ? updated : item)
      ),
      error: (error: HttpErrorResponse) => this.showError(error, '更新司機狀態失敗')
    });
  }

  private showError(error: HttpErrorResponse, fallback: string): void {
    const body = error.error as ApiError | null;
    this.errorMessage.set(body?.message ?? fallback);
  }
}
