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
