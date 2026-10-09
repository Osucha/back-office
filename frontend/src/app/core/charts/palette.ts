/** Chart colours shared by every ECharts option so the visualisations read as one system. */
export const CHART_COLORS = [
  '#3552d6',
  '#0e9c8d',
  '#d88a12',
  '#7a5bd6',
  '#3f9a52',
  '#eb2f96',
  '#faad14',
  '#2f54eb',
  '#a0d911',
  '#f5222d',
];

export const PROVIDER_COLORS: Record<string, string> = {
  ANTHROPIC: '#d4713b',
  OPENAI: '#10a37f',
  GOOGLE: '#4285f4',
  MISTRAL: '#ff7000',
  OLLAMA: '#5b5b5b',
  OPENAI_COMPATIBLE: '#13c2c2',
};

// Mid-tone neutrals that read on the light and the dark Relay surfaces (canvas charts cannot use
// CSS custom properties).
export const CHART_TEXT = '#8a8f96';
export const CHART_GRID = 'rgba(138, 143, 150, 0.22)';

export const baseChartOption = {
  color: CHART_COLORS,
  textStyle: { fontFamily: 'inherit', color: CHART_TEXT, fontSize: 12 },
  animationDuration: 400,
};
