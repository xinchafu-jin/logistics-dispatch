export type UserRole = 'ADMIN' | 'DRIVER';

export interface LoginRequest {
  account: string;
  password: string;
}

export interface AuthUser {
  accessToken: string;
  tokenType: string;
  expiresAt: string;
  role: UserRole;
  userId: number;
  account: string;
  name: string;
}

export interface ApiError {
  success?: boolean;
  message?: string;
}
