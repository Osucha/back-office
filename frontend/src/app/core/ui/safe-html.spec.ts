import { TestBed } from '@angular/core/testing';
import { DomSanitizer } from '@angular/platform-browser';
import { renderMarkdown, trustedMarkdown } from './markdown-viewer.component';
import {
  ALLOWED_URI_REGEXP,
  manualAnchorId,
  sanitizeHtml,
  sanitizeToTrustedHtml,
  type HtmlProfile,
} from './safe-html';

/** Parses sanitised HTML into a detached container for structural assertions. */
function dom(html: string): HTMLElement {
  const div = document.createElement('div');
  div.innerHTML = html;
  return div;
}

const PROFILES: HtmlProfile[] = ['untrusted', 'manual'];

describe('safe-html (the single sanitisation point, S6268)', () => {
  for (const profile of PROFILES) {
    describe(`profile ${profile}`, () => {
      const clean = (html: string) => sanitizeHtml(html, profile);

      it('drops script, style, iframe, object, embed, form controls, svg and math', () => {
        const out = clean(
          [
            '<p>ok</p>',
            '<script>alert(1)</script>',
            '<style>body{display:none}</style>',
            '<iframe src="https://evil.example"></iframe>',
            '<object data="x.swf"></object><embed src="x.swf">',
            '<form action="https://evil.example"><input name="password"><button>go</button></form>',
            '<textarea>t</textarea><select><option>o</option></select>',
            '<svg onload="alert(1)"><script>alert(2)</script></svg>',
            '<math><mtext><img src=x onerror=alert(3)></mtext></math>',
            '<link rel="stylesheet" href="https://evil.example/x.css"><meta http-equiv="refresh">',
            '<base href="https://evil.example/"><template><img src=x onerror=alert(4)></template>',
          ].join(''),
        );
        expect(out).toContain('<p>ok</p>');
        for (const bad of [
          '<script',
          '<style',
          '<iframe',
          '<object',
          '<embed',
          '<form',
          '<input',
          '<button',
          '<textarea',
          '<select',
          '<svg',
          '<math',
          '<link',
          '<meta',
          '<base',
          '<template',
          'onerror',
          'onload',
          'alert(',
        ])
          expect(out.toLowerCase()).not.toContain(bad);
      });

      it('removes every event handler, style, class and data attribute', () => {
        const el = dom(
          clean(
            '<img src="a.png" onerror="alert(1)" style="position:fixed" class="ant-modal-mask" data-x="1">' +
              '<a href="/x" onclick="alert(1)" onmouseover="alert(2)">x</a>' +
              '<p onpointerenter="alert(3)" aria-hidden="true">p</p>',
          ),
        );
        for (const node of Array.from(el.querySelectorAll('*')))
          for (const attr of Array.from(node.attributes))
            expect(['src', 'href']).toContain(attr.name);
      });

      it('blocks javascript:, vbscript:, data: and other schemes in links', () => {
        const el = dom(
          clean(
            [
              '<a href="javascript:alert(1)">1</a>',
              '<a href="JaVaScRiPt:alert(1)">2</a>',
              '<a href="java&#x09;script:alert(1)">3</a>',
              '<a href=" javascript:alert(1)">4</a>',
              '<a href="vbscript:msgbox(1)">5</a>',
              '<a href="data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==">6</a>',
              '<a href="file:///etc/passwd">7</a>',
              '<img src="javascript:alert(1)">',
            ].join(''),
          ),
        );
        for (const a of Array.from(el.querySelectorAll('a')))
          expect(a.hasAttribute('href')).toBe(false);
        expect(el.querySelector('img')?.hasAttribute('src')).toBe(false);
      });

      it('keeps http(s), mailto, relative, absolute-path and in-page links', () => {
        const el = dom(
          clean(
            [
              '<a href="https://example.com/a?b=c#d">1</a>',
              '<a href="http://example.com">2</a>',
              '<a href="mailto:a@b.c">3</a>',
              '<a href="/help/runs#run-list">4</a>',
              '<a href="#section">5</a>',
              '<a href="other.md">6</a>',
              '<a href="../x/y.png">7</a>',
              '<a href="?q=1">8</a>',
              '<img src="/manual/images/a.png" alt="a">',
            ].join(''),
          ),
        );
        expect(Array.from(el.querySelectorAll('a')).map((a) => a.getAttribute('href'))).toEqual([
          'https://example.com/a?b=c#d',
          'http://example.com',
          'mailto:a@b.c',
          '/help/runs#run-list',
          '#section',
          'other.md',
          '../x/y.png',
          '?q=1',
        ]);
        expect(el.querySelector('img')?.getAttribute('src')).toBe('/manual/images/a.png');
      });

      it('gives target=_blank links rel="noopener noreferrer" and drops other targets', () => {
        const el = dom(
          clean(
            '<a href="https://x.example" target="_blank" rel="opener">a</a>' +
              '<a href="https://x.example" target="_top">b</a>',
          ),
        );
        const [blank, top] = Array.from(el.querySelectorAll('a'));
        expect(blank.getAttribute('target')).toBe('_blank');
        expect(blank.getAttribute('rel')).toBe('noopener noreferrer');
        expect(top.hasAttribute('target')).toBe(false);
        expect(top.hasAttribute('rel')).toBe(false);
      });

      it('cannot clobber document or app globals (id/name)', () => {
        const out = clean(
          '<a id="cookie"></a><a name="location"></a><img name="getElementById">' +
            '<form name="body"><input name="cookie"></form><p id="manual-search">x</p>',
        );
        const host = dom(out);
        document.body.appendChild(host);
        try {
          expect(typeof document.cookie).toBe('string');
          expect(typeof document.getElementById).toBe('function');
          expect(document.body).toBeInstanceOf(HTMLBodyElement);
          expect(host.querySelector('[name]')).toBeNull();
          // no element can take an id the application itself uses
          for (const node of Array.from(host.querySelectorAll('[id]')))
            expect(node.id.startsWith('user-content-')).toBe(true);
        } finally {
          host.remove();
        }
      });

      it('makes code blocks keyboard-focusable', () => {
        expect(clean('<pre><code>x</code></pre><p>y</p>')).toBe(
          '<pre tabindex="0"><code>x</code></pre><p>y</p>',
        );
      });
    });
  }

  it('keeps manual anchors as user-content-<id> so fragments still resolve', () => {
    const html = renderMarkdown(
      '# T\n\n<a id="ci-token"></a>\n## Create a CI token\n\nSee [vars](/help/ci#ci-vars).\n',
      'manual',
    );
    const el = dom(html);
    expect(el.querySelector('#user-content-ci-token')).not.toBeNull();
    expect(el.querySelector('a[href="/help/ci#ci-vars"]')).not.toBeNull();
    expect(manualAnchorId('ci-token')).toBe('user-content-ci-token');
    expect(manualAnchorId('user-content-ci-token')).toBe('user-content-ci-token');
  });

  it('gives untrusted content no ids at all', () => {
    expect(renderMarkdown('<a id="x"></a>\n\ntext')).not.toContain('id=');
  });

  it('renders Markdown tables, lists, code, emphasis and links', () => {
    const el = dom(
      renderMarkdown(
        '| a | b |\n|:-|-:|\n| 1 | 2 |\n\n- **bold** _em_ `code`\n\n[l](https://x.example "t")',
      ),
    );
    expect(el.querySelector('table td')?.textContent).toBe('1');
    expect(el.querySelector('li strong')?.textContent).toBe('bold');
    expect(el.querySelector('a')?.getAttribute('title')).toBe('t');
  });

  it('neutralises XSS written in Markdown itself', () => {
    const out = renderMarkdown(
      '[x](javascript:alert(1)) ![i](x" onerror="alert(1)) <img src=x onerror=alert(1)>\n\n' +
        '<details open ontoggle="alert(1)"><summary>s</summary></details>',
    );
    const el = dom(out);
    // whatever survives as an element carries no handler and no script URL (text may mention them)
    for (const node of Array.from(el.querySelectorAll('*')))
      for (const attr of Array.from(node.attributes)) {
        expect(attr.name.startsWith('on')).toBe(false);
        expect(attr.value).not.toMatch(/javascript:/i);
      }
    expect(el.querySelector('a')?.hasAttribute('href')).toBe(false);
    expect(el.querySelector('details')).not.toBeNull();
  });

  it('the URI pattern accepts only http(s), mailto and scheme-less URLs', () => {
    for (const ok of [
      'https://a',
      'HTTP://a',
      'mailto:x',
      '/a',
      '#a',
      'a/b',
      'a.png',
      '?x',
      '//h/p',
      '',
    ])
      expect(ALLOWED_URI_REGEXP.test(ok)).toBe(true);
    for (const bad of ['javascript:x', 'data:x', 'vbscript:x', 'file:/x', 'blob:x', 'a:b/c'])
      expect(ALLOWED_URI_REGEXP.test(bad)).toBe(false);
  });

  it('hands only sanitised HTML to bypassSecurityTrustHtml', () => {
    const sanitizer = TestBed.inject(DomSanitizer);
    let received = '';
    const spy = vi.spyOn(sanitizer, 'bypassSecurityTrustHtml').mockImplementation((v) => {
      received = v;
      return v as never;
    });
    sanitizeToTrustedHtml(sanitizer, '<img src=x onerror=alert(1)><p>ok</p>');
    expect(received).toBe('<img src="x"><p>ok</p>');
    trustedMarkdown(sanitizer, '<script>alert(1)</script>\n\n**b**');
    expect(received).not.toContain('script');
    expect(received).toContain('<p><strong>b</strong></p>');
    expect(spy).toHaveBeenCalledTimes(2);
    spy.mockRestore();
  });

  it('returns empty output for empty input', () => {
    expect(sanitizeHtml('')).toBe('');
    expect(renderMarkdown(null)).toBe('');
  });
});
