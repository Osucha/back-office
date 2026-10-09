/**
 * Tree-shaken ECharts bundle: only the renderers, charts and components the app uses.
 * Imported lazily by `provideEchartsCore` in app.config.ts.
 */
import * as echarts from 'echarts/core';
import { BarChart, LineChart, PieChart, ScatterChart } from 'echarts/charts';
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components';
import { LabelLayout, UniversalTransition } from 'echarts/features';
import { CanvasRenderer } from 'echarts/renderers';

echarts.use([
  BarChart,
  LineChart,
  PieChart,
  ScatterChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  DataZoomComponent,
  MarkLineComponent,
  LabelLayout,
  UniversalTransition,
  CanvasRenderer,
]);

// ngx-echarts calls `init`, `getInstanceByDom`, `connect`, ... on the module it receives from
// `provideEchartsCore`, so the module must expose the echarts/core API at top level.
export * from 'echarts/core';
export default echarts;
export { echarts };
