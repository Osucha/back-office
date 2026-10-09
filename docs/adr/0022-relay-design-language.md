# 0022 - "Relay" design language: the UI is organised around who holds the baton

Status: accepted - 2026-09-27. Refines [0010](0010-angular-ng-zorro-frontend.md) (Angular and NG-ZORRO stay).

## Context
The first three iterations used NG-ZORRO with default Ant Design styling. It works but looks like every
admin panel and does not express what the product is for: handing work to agents and CI and stepping in
only when needed. The owner asked for an interface that is efficient, comfortable, readable, original
rather than derivative, and still professional and ergonomic.

## Decision
- Adopt the **Relay** design language (`docs/ui/design-language.md`): the baton (agent / you / ci / done /
  blocked / idle) is the single status vocabulary; the home page is a *Your turn* queue; runs show a
  relay track; a command palette and keyboard shortcuts make every action reachable without the mouse.
- Visual identity: warm paper and graphite neutrals, ink primary buttons, a cobalt accent for navigation
  and focus, amber reserved for "your turn"; Instrument Sans and JetBrains Mono, self-hosted; hairlines
  instead of shadows.
- Implementation: NG-ZORRO remains the component base, themed through its CSS-variable theme and design
  tokens (no overriding of `.ant-*` internals except in one documented theme file); Relay components are
  standalone Angular components in `core/ui/relay/`.
- Quality gates: axe checks in the e2e smoke (no serious/critical violations), bundle budget, visual
  review of screenshots for every main route in light and dark.

## Alternatives
- Replace NG-ZORRO with a headless kit (Angular CDK + own components): maximum originality, but months of
  work for tables, forms and pickers that NG-ZORRO already does well.
- Keep the defaults: fastest, but the product would keep looking generic and "man when needed" would stay
  an idea rather than a layout.

## Consequences
- All status tags are replaced by the baton chip; new features must map their states to a holder.
- Fonts add ~120 kB (woff2, cached); within the budget.
