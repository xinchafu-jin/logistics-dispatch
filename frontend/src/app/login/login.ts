import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { ApiError, UserRole } from '../auth/auth.models';
import { AuthService } from '../auth/auth.service';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './login.html',
  styleUrl: './login.css'
})
export class Login implements OnInit {
  private readonly formBuilder = inject(FormBuilder);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);

  protected readonly role = signal<UserRole>('ADMIN');
  protected readonly isManager = computed(() => this.role() === 'ADMIN');
  protected readonly loading = signal(false);
  protected readonly errorMessage = signal('');
  protected readonly form = this.formBuilder.nonNullable.group({
    account: ['', Validators.required],
    password: ['', Validators.required]
  });

  ngOnInit(): void {
    this.route.data.subscribe((data) => {
      const role = data['role'] as UserRole;
      this.role.set(role);
      this.errorMessage.set('');
      if (this.auth.hasRole(role)) {
        void this.router.navigate([role === 'ADMIN' ? '/manager' : '/driver']);
      }
    });
  }

  protected submit(): void {
    if (this.form.invalid || this.loading()) {
      this.form.markAllAsTouched();
      return;
    }

    this.loading.set(true);
    this.errorMessage.set('');
    this.auth.login(this.role(), this.form.getRawValue())
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: () => void this.router.navigate([
          this.role() === 'ADMIN' ? '/manager' : '/driver'
        ]),
        error: (error: HttpErrorResponse) => {
          const body = error.error as ApiError | null;
          this.errorMessage.set(body?.message ?? '無法登入，請確認後端服務已啟動');
        }
      });
  }
}
