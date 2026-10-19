import { HttpContextToken } from '@angular/common/http';
import { InjectionToken } from '@angular/core';

export interface ApiConfig {
  /** Base path of the backend REST API (proxied to Spring Boot in development). */
  baseUrl: string;
  /** Base path of Spring Boot Actuator. */
  actuatorUrl: string;
}

export const API_CONFIG = new InjectionToken<ApiConfig>('API_CONFIG', {
  providedIn: 'root',
  factory: () => ({ baseUrl: '/api', actuatorUrl: '/actuator' }),
});

/** Set on a request to keep the error interceptor from showing a toast (e.g. background health polling). */
export const SILENT_ERRORS = new HttpContextToken<boolean>(() => false);
