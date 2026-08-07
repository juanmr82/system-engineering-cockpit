// The tree-shaken echarts build (ADR 0008). Only the chart types and components this application
// actually draws are registered, so the bundle carries the bar/grid/tooltip path and not the
// geo, graph, calendar, financial or 3D machinery.
//
// `export *` is required, not stylistic: ngx-echarts resolves the loader with
// `load().then(({ init }) => …)`, so the resolved module must expose `init` as a named export.
//
// Add to `use()` when a chart genuinely needs a new type. Importing from 'echarts' wholesale
// anywhere in the app would silently undo the whole arrangement.
import * as echarts from 'echarts/core';
import { BarChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

echarts.use([BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

export * from 'echarts/core';
