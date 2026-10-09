/**
 * Less plugin for the Relay NG-ZORRO theme (ng-zorro-theme.less).
 *
 * NG-ZORRO's less derives colours with `fade()`, `shade()`, `tint()`, `darken()`, ... from its
 * variables. The Relay theme sets those variables to CSS custom properties (`var(--bo-*)`), which
 * less cannot compute with. This plugin keeps the built-in behaviour for real colours and, for a
 * `var(...)` argument, emits the equivalent CSS `color-mix()` so the result follows the theme at
 * runtime (light, dark) instead of being frozen at build time.
 */
module.exports = {
  install(less, pluginManager, functions) {
    const registry = less.functions.functionRegistry;
    const builtin = (name) => registry.get(name);
    const isColor = (node) => node && node.rgb !== undefined;
    const css = (node) => (node && typeof node.toCSS === 'function' ? node.toCSS({}) : String(node));
    const pct = (amount) => {
      if (!amount) return 0;
      const v = Number(amount.value);
      return amount.unit && amount.unit.is && amount.unit.is('%') ? v : v * 100;
    };
    const anon = (value) => new less.tree.Anonymous(value);
    const mixWith = (color, other, weightOfOther) =>
      anon(`color-mix(in srgb, ${css(color)}, ${other} ${Math.max(0, Math.min(100, weightOfOther))}%)`);

    const wrap = (name, fallback) => {
      const original = builtin(name);
      functions.add(name, function (color, amount, ...rest) {
        if (isColor(color) && original) return original.call(this, color, amount, ...rest);
        return fallback(color, amount);
      });
    };

    // fade(c, 20%) -> c at 20 % opacity
    wrap('fade', (c, a) => anon(`color-mix(in srgb, ${css(c)} ${pct(a)}%, transparent)`));
    // fadein / fadeout change the alpha relative to the colour's own; a var is treated as opaque.
    wrap('fadein', (c) => c);
    wrap('fadeout', (c, a) => anon(`color-mix(in srgb, ${css(c)} ${100 - pct(a)}%, transparent)`));
    wrap('tint', (c, a) => mixWith(c, 'white', pct(a)));
    wrap('shade', (c, a) => mixWith(c, 'black', pct(a)));
    wrap('lighten', (c, a) => mixWith(c, 'white', pct(a)));
    wrap('darken', (c, a) => mixWith(c, 'black', pct(a)));
    wrap('saturate', (c) => c);
    wrap('desaturate', (c) => c);
    wrap('spin', (c) => c);
  },
};
