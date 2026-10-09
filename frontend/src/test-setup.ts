// Vitest setup (angular.json test.setupFiles), ADR 0026. `$localize` comes from the build target's
// polyfills ('@angular/localize/init' in angular.json build.options.polyfills), which the unit-test
// builder loads before this file; importing the polyfill here again makes the builder warn
// ("Direct import of '@angular/localize/init' detected"). This check makes a missing polyfill fail
// loudly instead of every spec that touches a translated string.
if (typeof (globalThis as { $localize?: unknown }).$localize !== 'function') {
  throw new Error(
    "$localize is not defined: keep '@angular/localize/init' in angular.json build.options.polyfills",
  );
}
export {};
