import { ApplicationConfig, provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideEchartsCore } from 'ngx-echarts';
import { provideSecIcons } from './core/icons/sec-icons';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    provideRouter(routes),
    provideHttpClient(withFetch()),
    provideAnimationsAsync(),
    provideSecIcons(),
    // The tree-shaken core (ADR 0008), loaded lazily so echarts is not in the initial bundle —
    // only the Statistics route draws anything. `shared/charts/echarts-core` is the single place
    // chart types are registered; never point this at 'echarts' wholesale.
    provideEchartsCore({ echarts: () => import('./shared/charts/echarts-core') }),
  ],
};
