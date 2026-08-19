export type UserRole = 'DRIVER' | 'DISPATCHER';

export type LoginPortal = 'ADMIN' | 'DRIVER';

export interface AuthUser {
  account: string;
  displayName: string;
  role: UserRole;
}

export interface LoginCredentials {
  account: string;
  password: string;
  portal: LoginPortal;
}
