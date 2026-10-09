// i18n-ignore-file: design tokens, no UI text.
/**
 * Chart colours from the Relay tokens (`styles/relay/tokens.css`). ECharts draws on a canvas and
 * cannot use CSS custom properties, so the values are read at runtime with `getComputedStyle` and
 * re-read whenever the theme changes (`chartTokensSignal()`), so charts follow light and dark.
 *
 *   readonly tokens = chartTokensSignal();
 *   readonly option = computed(() => myOption(this.data(), this.tokens()));
 *
 * Pure helpers (`chartTokens`, `providerColor`, `seriesColor`, `chartBase`, `chartAxis`) take a
 * reader or the resolved tokens, so they are testable without a DOM.
 */
import { DestroyRef, computed, inject, signal, type Signal } from '@angular/core';
import type { BatonHolder } from './baton';

export interface ChartTokens {
  ink: string;
  ink2: string;
  ink3: string;
  line: string;
  lineStrong: string;
  surface: string;
  surface2: string;
  accent: string;
  holder: Record<BatonHolder, string>;
  /** Categorical series colours, in order (accent, ci, you, done, graphite, blocked, idle). */
  series: string[];
  font: string;
  mono: string;
}

export type TokenReader = (name: string) => string;

/** Light-theme values, used when a token cannot be read (tests, server rendering). */
export const CHART_TOKEN_FALLBACK: Readonly<Record<string, string>> = {
  '--bo-ink': '#1c1e21',
  '--bo-ink-2': '#4a4e55',
  '--bo-ink-3': '#6b7078',
  '--bo-line': '#e3e0d8',
  '--bo-line-strong': '#cfcbc1',
  '--bo-surface': '#ffffff',
  '--bo-surface-2': '#faf9f6',
  '--bo-accent': '#3552d6',
  '--bo-agent': '#3552d6',
  '--bo-you': '#c77800',
  '--bo-ci': '#0e7c71',
  '--bo-done': '#2f7d40',
  '--bo-blocked': '#b8392a',
  '--bo-idle': '#8a8f96',
  '--bo-font-ui': 'Instrument Sans Variable, system-ui, sans-serif',
  '--bo-font-mono': 'JetBrains Mono Variable, ui-monospace, monospace',
};

/** Resolves the tokens through `read`; empty values fall back to the light theme. */
export function chartTokens(read: TokenReader = () => ''): ChartTokens {
  const v = (name: string) => (read(name) || '').trim() || CHART_TOKEN_FALLBACK[name] || '';
  const holder: Record<BatonHolder, string> = {
    agent: v('--bo-agent'),
    you: v('--bo-you'),
    ci: v('--bo-ci'),
    done: v('--bo-done'),
    blocked: v('--bo-blocked'),
    idle: v('--bo-idle'),
  };
  return {
    ink: v('--bo-ink'),
    ink2: v('--bo-ink-2'),
    ink3: v('--bo-ink-3'),
    line: v('--bo-line'),
    lineStrong: v('--bo-line-strong'),
    surface: v('--bo-surface'),
    surface2: v('--bo-surface-2'),
    accent: v('--bo-accent'),
    holder,
    series: [
      v('--bo-accent'),
      holder.ci,
      holder.you,
      holder.done,
      v('--bo-ink-2'),
      holder.blocked,
      holder.idle,
    ],
    font: v('--bo-font-ui'),
    mono: v('--bo-font-mono'),
  };
}

/** Reads custom properties from the document root (the element that carries `data-theme`). */
export function cssTokenReader(el?: Element | null): TokenReader {
  const root = el ?? (typeof document !== 'undefined' ? document.documentElement : null);
  if (!root || typeof getComputedStyle === 'undefined') return () => '';
  const style = getComputedStyle(root);
  return (name) => style.getPropertyValue(name);
}

/** The n-th categorical colour (wraps around). */
export function seriesColor(index: number, t: ChartTokens): string {
  const n = t.series.length;
  return t.series[((index % n) + n) % n];
}

const PROVIDER_SLOT: Record<string, number> = {
  ANTHROPIC: 2, // amber
  OPENAI: 1, // teal
  GOOGLE: 0, // cobalt
  MISTRAL: 5, // brick
  OLLAMA: 4, // graphite
  OPENAI_COMPATIBLE: 3, // moss
};

/** A stable colour per LLM provider, from the categorical token series. */
export function providerColor(provider: string | null | undefined, t: ChartTokens): string {
  const slot = PROVIDER_SLOT[provider ?? ''];
  return slot === undefined ? t.holder.idle : seriesColor(slot, t);
}

/** Base ECharts option: palette, text and tooltip on the Relay surfaces. */
export function chartBase(t: ChartTokens): Record<string, unknown> {
  return {
    color: t.series,
    textStyle: { fontFamily: t.font, color: t.ink3, fontSize: 12 },
    tooltip: {
      backgroundColor: t.surface,
      borderColor: t.line,
      borderWidth: 1,
      textStyle: { color: t.ink, fontSize: 12, fontFamily: t.font },
      extraCssText: 'border-radius:8px;box-shadow:0 8px 24px rgba(0,0,0,.12);',
    },
    animationDuration: 300,
  };
}

/** Axis styling: hairline axis, quiet labels, dashed split lines. */
export function chartAxis(t: ChartTokens): {
  axisLine: Record<string, unknown>;
  axisTick: Record<string, unknown>;
  axisLabel: Record<string, unknown>;
  splitLine: Record<string, unknown>;
  nameTextStyle: Record<string, unknown>;
} {
  return {
    axisLine: { lineStyle: { color: t.lineStrong } },
    axisTick: { show: false },
    axisLabel: { color: t.ink3, fontSize: 11 },
    splitLine: { lineStyle: { color: t.line, type: 'dashed' } },
    nameTextStyle: { color: t.ink3, fontSize: 11 },
  };
}

/**
 * Chart tokens that follow the theme: re-read when `html[data-theme]` / `html.class` change or the
 * OS colour scheme flips. Call in an injection context.
 */
export function chartTokensSignal(): Signal<ChartTokens> {
  const version = signal(0);
  const bump = () => version.update((v) => v + 1);
  if (typeof document !== 'undefined' && typeof MutationObserver !== 'undefined') {
    const observer = new MutationObserver(bump);
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme', 'class', 'data-density'],
    });
    const media = window.matchMedia?.('(prefers-color-scheme: dark)');
    media?.addEventListener?.('change', bump);
    inject(DestroyRef).onDestroy(() => {
      observer.disconnect();
      media?.removeEventListener?.('change', bump);
    });
  }
  return computed(() => {
    version();
    return chartTokens(cssTokenReader());
  });
}
