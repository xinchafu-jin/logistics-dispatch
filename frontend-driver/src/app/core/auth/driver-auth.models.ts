export interface DriverUser {
  id: number;
  account: string;
  name: string;
  role: 'DRIVER';
}

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresAt: string;
  role: 'ADMIN' | 'DRIVER';
  userId: number;
  account: string;
  name: string;
}

export interface CurrentUserResponse {
  userId: number;
  account: string;
  name: string;
  role: 'ADMIN' | 'DRIVER';
}
