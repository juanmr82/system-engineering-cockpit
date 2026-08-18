# ADR 0003: The paper visual style, and how it maps onto Material

Status: accepted
Date: 2026-08-05

## Context

A design proposal (`proposed_new_style.md`, handed over as an external document and **never
committed to this repository** — this ADR and `frontend/src/styles/_tokens.scss` are what
survive of it) arrived as a plain stylesheet — a design for a requirement-tree
document viewer, with its own token set (`--ab-*`), a paper-on-desk surface model, and a
vocabulary of cards, depth rails and annotation panels. It is the intended look for the product,
but it covers about half of what the application actually renders: it has no navigation, no data
table, no dialog, no tabs and no form controls, and the app has all of those and no tree view yet.

Adopting it therefore meant three decisions, none of which is recoverable cheaply once components
start being written against the result.

**Whose token names.** The proposal's palette values are identical to the ones already in
`_tokens.scss` — the same Airbus blues, green and red — under different names. Keeping both
would mean two token systems for one palette, and CLAUDE.md §6 requires a single emitter.

**What the neutrals are.** CLAUDE.md §8 said to build greys from percentages of black rather than
invent a ramp. The proposal invents one, and it is cooled towards the blue.

**How to reach Material.** Every extrapolated component — table, dialog, tabs, nav, inputs — is a
Material component whose defaults are rounded, roomy and Material-blue.

## Decision

**The palette keeps its `--sec-*` names; the proposal contributes what was missing.** Surfaces,
the ink ramp, the type scale and the geometry tokens are new; the brand colours are not renamed.
`_tokens.scss` carries the `--ab-*` → `--sec-*` mapping in a comment so the proposal stays
readable beside the code.

**The cooled neutral ramp is adopted, and §8 is amended to say so.** Against Airbus blue a
pure-grey ramp reads as dirty, and white sheets on a neutral grey read as holes rather than as
paper. The ramp is a closed token set, extended in `_tokens.scss` or not at all.

**Extrapolation follows four stated rules**, written at the top of `_mixins.scss`: separate with a
hairline; squared corners; colour is a rail, not a fill; non-content text is a 10px uppercase
micro-label. Every pattern the proposal does not define is derived from those rather than invented,
so the additions look like the same system rather than like a second designer's work.

**Material is reached only through M3 component token overrides**, all of them in `_theme.scss`.
No `::ng-deep` and no `.mat-mdc-*` selectors.

Two rules from the proposal are **not** adopted:

- **Italic empty states.** "Never italic" is an explicit Airbus rule with no caption or
  placeholder exception (§8), and `styles.scss` enforces it globally. Absence is carried by
  `--sec-ink-3`, the non-content ink, instead.
- **A second `--ab-*` token layer**, per the naming decision above.

And one rule is **deliberately broken**: the Tier-2 chip is filled, not railed. R2 says a user must
never mistake what the application added for what DOORS said, and that is not a distinction to
make subtle for the sake of consistency.

## Consequences

The style is now one system: a new view composes mixins and gets the look for free, and a value
that is not in the scale is a signal the scale is wrong rather than licence to hardcode. The cost
is that `_theme.scss` is long and its keys are Material's, so a Material major upgrade means
re-checking token names — which is still far better than re-checking `::ng-deep` selectors, since
a renamed token is a Sass error and a renamed internal class is a silent visual regression.

The document vocabulary lives in `_document.scss` with no consumer. It emits nothing until
included, so it costs no bytes; the risk is that it drifts from the views eventually built against
it. That is preferable to leaving the specified look only in an untracked stylesheet.

Two defects surfaced while verifying the restyle and were fixed alongside it: the Material drawer
defaults to a 360px container against §9's 280px nav, which rendered as a band of empty white once
the content area became grey; and `/requirements/review` shipped the string `:__Meta` in its
user-visible description, which R5 forbids.
