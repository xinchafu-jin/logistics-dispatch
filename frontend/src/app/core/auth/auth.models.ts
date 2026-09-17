export type UserRole = 'DISPATCHER';

export interface AuthUser {
  account: string;
  displayName: string;
  role: UserRole;
}

export interface LoginCredentials {
  account: string;
  password: string;
}

export interface AdminPasswordResetVerification {
  account: string;
  phone: string;
}

export interface AdminPasswordResetRequest extends AdminPasswordResetVerification {
  newPassword: string;
}
