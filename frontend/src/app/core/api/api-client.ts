import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';

// The one HTTP client wrapper for the app (CLAUDE.md §11: "exactly one HTTP client").
// Endpoints are added here as the corresponding backend routes land.
@Injectable({ providedIn: 'root' })
export class ApiClient {
  private readonly http = inject(HttpClient);
}
