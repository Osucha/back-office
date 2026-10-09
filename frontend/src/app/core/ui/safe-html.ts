import type { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import DOMPurify, { type Config, type DOMPurify as Purifier } from 'dompurify';

/**
 * The ONE place where the app hands HTML to Angular past its own sanitiser (SonarQube
 * typescript:S6268, accepted for this file only in backend/build.gradle.kts and
 * docs/quality/sonarqube-baseline.md). Everything that renders HTML (`app-markdown`, the help panel,
 * the manual page) goes through `sanitizeToTrustedHtml`, which runs DOMPurify with an explicit
 * allow-list first. Angular's built-in sanitiser would also be safe, but it strips the `id` of the
 * manual's anchors and gives no control over URLs, link targets and DOM clobbering.
 *
 * Sources of the HTML and why they count as hostile:
 * - `untrusted`: Markdown produced by coding agents and LLMs (run artefacts, benchmark attempts and
 *   judge rationales, CI review summaries, skills, project constitutions, task rubrics). Prompt
 *   injection can make it contain anything, so it gets no ids, no styles, no forms, no scripts.
 * - `manual`: the user manual served from `/manual` (repository content, reviewed). Same rules, plus
 *   `id` on elements so `#fragment` links can jump to the manual's `<a id="...">` anchors.
 *
 * In the manual profile `id` values are prefixed with `user-content-` (DOMPurify
 * `SANITIZE_NAMED_PROPS`; `name` is never allowed), so rendered content can never clobber
 * `document.cookie`, a form field or an id of the application itself; fragment scrolling looks up
 * `manualAnchorId(anchor)`.
 */
export type HtmlProfile = 'untrusted' | 'manual';

/** Prefix that DOMPurify puts in front of every `id` and `name` (DOM clobbering protection). */
export const USER_CONTENT_PREFIX = 'user-content-';

/** The DOM id of a manual anchor `<a id="anchor">` after sanitising. */
export function manualAnchorId(anchor: string): string {
  return anchor.startsWith(USER_CONTENT_PREFIX) ? anchor : USER_CONTENT_PREFIX + anchor;
}

/** Elements Markdown (CommonMark + GFM) produces, plus a few harmless inline ones. */
// prettier-ignore
const ALLOWED_TAGS = [
  'a', 'abbr', 'b', 'blockquote', 'br', 'code', 'dd', 'del', 'details', 'div', 'dl', 'dt', 'em',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'hr', 'i', 'img', 'ins', 'kbd', 'li', 'mark', 'ol', 'p',
  'pre', 'q', 's', 'samp', 'small', 'span', 'strong', 'sub', 'summary', 'sup', 'table', 'tbody',
  'td', 'tfoot', 'th', 'thead', 'tr', 'u', 'ul', 'var',
];

/** Never allowed, even if a future edit widens ALLOWED_TAGS (defence in depth). */
// prettier-ignore
const FORBID_TAGS = [
  'script', 'style', 'iframe', 'frame', 'frameset', 'object', 'embed', 'applet', 'form', 'input',
  'button', 'textarea', 'select', 'option', 'svg', 'math', 'link', 'meta', 'base', 'template',
  'noscript',
];

/** Attributes without any event handler (`on*`), `style`, `class` or `data-*`. */
// prettier-ignore
const ALLOWED_ATTR = [
  'href', 'title', 'alt', 'src', 'width', 'height', 'align', 'start', 'colspan', 'rowspan',
  'open', 'target',
];

/**
 * URLs in `href`/`src`: http(s), mailto, protocol-relative, absolute paths, relative paths and
 * in-page anchors. Anything with another scheme (`javascript:`, `data:`, `vbscript:`, `file:` ...)
 * is dropped: a relative URL has no `:` before its first `/`, `?` or `#`. DOMPurify itself still
 * lets `data:` through on `<img src>` (inert: images cannot run script; the CSP allows `img-src data:`).
 * Linear: the character class and the follow-up set are disjoint, so there is no backtracking.
 */
export const ALLOWED_URI_REGEXP = /^(?:(?:https?|mailto):|[^:/?#]*(?:[/?#]|$))/i;

function config(profile: HtmlProfile): Config {
  return {
    ALLOWED_TAGS,
    ALLOWED_ATTR: profile === 'manual' ? [...ALLOWED_ATTR, 'id'] : ALLOWED_ATTR,
    FORBID_TAGS,
    FORBID_ATTR: ['style', 'class', 'srcset', 'formaction', 'xlink:href', 'name'],
    ALLOWED_URI_REGEXP,
    ALLOW_DATA_ATTR: false,
    ALLOW_ARIA_ATTR: false,
    ALLOW_UNKNOWN_PROTOCOLS: false,
    ALLOW_SELF_CLOSE_IN_ATTR: false,
    SANITIZE_DOM: true,
    SANITIZE_NAMED_PROPS: true,
    KEEP_CONTENT: true,
    RETURN_TRUSTED_TYPE: false,
  };
}

let purifier: Purifier | null = null;

/** A private DOMPurify instance, so these hooks never affect other users of the library. */
function instance(): Purifier {
  if (purifier) return purifier;
  const p = DOMPurify(window);
  p.addHook('afterSanitizeAttributes', (node) => {
    if (node.nodeName === 'A' && node.hasAttribute('target')) {
      // Only new-tab links, and never with access to window.opener or the referrer.
      if (node.getAttribute('target') === '_blank') node.setAttribute('rel', 'noopener noreferrer');
      else node.removeAttribute('target');
    } else if (node.nodeName === 'PRE') {
      // Long code examples overflow horizontally: keyboard-scrollable (axe scrollable-region-focusable).
      node.setAttribute('tabindex', '0');
    }
  });
  purifier = p;
  return p;
}

/** Sanitised HTML as a string (for tests and callers that do not bind it). */
export function sanitizeHtml(html: string, profile: HtmlProfile = 'untrusted'): string {
  if (!html) return '';
  return instance().sanitize(html, config(profile)) as string;
}

/**
 * Sanitises `html` with DOMPurify and marks the result as trusted for `[innerHTML]`. The bypass
 * below is the only one in the application and only ever receives DOMPurify output.
 */
export function sanitizeToTrustedHtml(
  sanitizer: DomSanitizer,
  html: string,
  profile: HtmlProfile = 'untrusted',
): SafeHtml {
  return sanitizer.bypassSecurityTrustHtml(sanitizeHtml(html, profile));
}
