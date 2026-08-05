// Project-specific lint rules. CLAUDE.md §11: "A lint rule catching `__` inside `.html` templates
// is worth the ten minutes."

/**
 * R5 — the `__` namespace is internal and never reaches the user.
 *
 * The hard part is telling an internal name from BEM. Both use a double underscore, and the
 * codebase is full of `class="sec-modules__header"`, which is correct and must not be flagged.
 *
 * The distinction is what precedes the underscores. A BEM element always has a block name in
 * front of it (`sec-modules__header`), while an internal name always starts one: `__name`,
 * `:__Meta`, `(Tier-2 :__Meta data)`. So the pattern is "`__` **not** preceded by a word
 * character", which separates the two cases exactly and needs no AST walk over class attributes.
 *
 * Applied to two things:
 *   - `.html` — the whole template, as raw text.
 *   - `.ts` — string and template literals only, so an inline `template:` and any user-facing
 *     string are checked while a comment explaining `__updatedAt` is not. Comments are where
 *     these names *should* appear.
 */
const INTERNAL_NAME = /(?<![A-Za-z0-9])__[A-Za-z]\w*/g;

const MESSAGE =
  "'{{name}}' is an internal property name and must never be shown to a user (CLAUDE.md R5). " +
  'Add a display label to domain/Aliases.kt on the backend and send that instead.';

/**
 * True for the template literal of an inline `@Component({ template: `…` })`.
 *
 * angular-eslint's processInlineTemplates extracts those into a virtual .html file that is then
 * linted by the `**\/*.html` config — this same rule included. Without this check every finding
 * in an inline template is reported twice, once per pass.
 */
function isInlineComponentTemplate(node) {
  const property = node.parent?.parent;
  return (
    property?.type === 'Property' &&
    !property.computed &&
    (property.key?.name === 'template' || property.key?.value === 'template')
  );
}

/** Report every match of INTERNAL_NAME in `text`, offsetting positions by `baseIndex`. */
function reportMatches(context, text, baseIndex) {
  for (const match of text.matchAll(INTERNAL_NAME)) {
    const start = baseIndex + match.index;
    context.report({
      loc: {
        start: context.sourceCode.getLocFromIndex(start),
        end: context.sourceCode.getLocFromIndex(start + match[0].length),
      },
      messageId: 'internalName',
      data: { name: match[0] },
    });
  }
}

const noInternalNamespace = {
  meta: {
    type: 'problem',
    docs: {
      description: 'Disallow `__`-prefixed internal names in anything a user can read (R5).',
    },
    schema: [],
    messages: { internalName: MESSAGE },
  },
  create(context) {
    const isTemplate = context.filename.endsWith('.html');

    if (isTemplate) {
      // The angular-eslint template parser produces its own AST; Program is the one node whose
      // name is stable across it and espree, and the raw text is all this rule needs.
      return {
        Program() {
          reportMatches(context, context.sourceCode.getText(), 0);
        },
      };
    }

    return {
      Literal(node) {
        if (typeof node.value === 'string') {
          // +1 skips the opening quote so the reported column lands on the name itself.
          reportMatches(context, node.value, node.range[0] + 1);
        }
      },
      TemplateElement(node) {
        if (isInlineComponentTemplate(node)) {
          return;
        }
        reportMatches(context, node.value.raw, node.range[0]);
      },
    };
  },
};

export default {
  meta: { name: 'sec' },
  rules: { 'no-internal-namespace': noInternalNamespace },
};
