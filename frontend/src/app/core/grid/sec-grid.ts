import { AllCommunityModule, ModuleRegistry, themeQuartz } from 'ag-grid-community';
import type { ColDef, GridOptions } from 'ag-grid-community';

// ag-grid Community, set up once for the whole application (ADR 0006). Every data table in the
// app is this grid — there is no second table system.
//
// Two things live here and nowhere else: the module registration, and the options every grid
// starts from. Everything *visual* lives in styles/_grid.scss instead, because ag-grid emits its
// theme parameters as --ag-* custom properties inside a zero-specificity :where() rule, so a plain
// class selector overrides them. That is what keeps the grid on the same --sec-* ramp as the rest
// of the app with no colour, size or radius written in TypeScript.

// The Community feature set, registered once, when this file is first loaded.
//
// **This is deliberately not a `provide…()` in `appConfig`, and that is a bundle decision, not a
// style lapse.** ag-grid is ~1.5 MB and the app's initial-bundle budget is 1 MB; every grid view is
// a lazy route. A provider — whether in `appConfig` or in a route's `providers` array — has to be
// imported by `app.config.ts` or `app.routes.ts`, both of which are in the initial chunk, and that
// import alone drags ag-grid in with it and fails the build. Registration therefore rides along
// with the module that already has to be loaded before a grid can render.
//
// It is also not really DI state. `ModuleRegistry` is a global ag-grid owns; routing it through an
// injector would buy nothing but the appearance of consistency.
//
// `AllCommunityModule` is the whole MIT bundle rather than a hand-picked list. Registering
// `ClientSideRowModelModule`, `ColumnAutoSizeModule`, … individually trades a bundle saving for a
// failure mode that is very hard to read: a missing module makes a feature silently do nothing
// rather than fail. If bundle size ever becomes the binding constraint, narrow this list and the
// ADR that justifies it, together.
ModuleRegistry.registerModules([AllCommunityModule]);

/**
 * The column defaults every SEC table starts from.
 *
 * Deliberately absent: `field`. ag-grid reads a dot in `field` as a property path, so a column
 * declared `field: 'REQ. Priorität'` looks for `row['REQ']['Priorität']`, finds nothing and
 * renders blank with no error at all. DOORS attribute names carry dots, spaces, slashes and
 * umlauts, so every column in this application uses a synthetic `colId` and a `valueGetter`
 * (CLAUDE.md §6, §11).
 */
export const SEC_GRID_DEFAULT_COL_DEF: ColDef = {
  resizable: true,
  sortable: true,
  // Requirement statements are paragraphs, not labels. Truncating one to a single line and
  // putting the rest in a tooltip means a reviewer cannot read down a column at all — so cells
  // wrap and the row grows to fit its tallest cell.
  //
  // `wrapText` without `autoHeight` clips at the fixed row height, and `autoHeight` without
  // `wrapText` has nothing to grow for: they are one setting in two properties.
  wrapText: true,
  autoHeight: true,
  // The same argument, applied to the header. A DOORS attribute name is a phrase — "REQ. Verifi-
  // cation Method", "SYS. Rationale for Allocation" — and truncating it to one line leaves several
  // columns whose headers differ only past the ellipsis. The pair works the way `wrapText` and
  // `autoHeight` do: `wrapHeaderText` alone clips at the fixed header height, and
  // `autoHeaderHeight` alone has nothing to grow for.
  //
  // The header row grows to its tallest label and every column keeps the same header height, so
  // this costs vertical space once, at the top, rather than per row.
  wrapHeaderText: true,
  autoHeaderHeight: true,
  // The header cell is ag-grid's own DOM, so it cannot be reached from a component stylesheet.
  // `headerClass` is the public way in: the class is ours, declared in styles/_grid.scss, and no
  // rule anywhere targets an .ag-* internal.
  headerClass: 'sec-grid__header-cell',
  cellClass: 'sec-grid__cell',
  // Long DOORS text would otherwise stretch a column past the viewport on autosize.
  minWidth: 80,
};

/**
 * The grid options every SEC table starts from.
 *
 * A function rather than a constant: one options object shared between two live grids is one
 * object two grids can disagree about, and the bug that produces is invisible until both are open.
 */
export function secGridOptions<TRow>(): GridOptions<TRow> {
  return {
    theme: themeQuartz,
    // Airbus GDPR guidance forbids the Google Fonts CDN and the app self-hosts Inter (CLAUDE.md
    // §8). ag-grid's icons are inline data URIs, so nothing else here reaches the network.
    loadThemeGoogleFonts: false,
    defaultColDef: SEC_GRID_DEFAULT_COL_DEF,
    // Motion is functional only (CLAUDE.md §8). Rows sliding on sort is decoration, and at ~1 000
    // rows it is decoration that costs a frame.
    animateRows: false,
    suppressColumnMoveAnimation: true,
    // A cell is read, copied and tabbed through, never treated as a spreadsheet selection.
    enableCellTextSelection: true,
    suppressCellFocus: false,
    // Sort cycles through ascending → descending → back to the source order the view loaded in.
    // For the review table that source order is document order (`__sortKey`), which is why `null`
    // has to be in the cycle rather than sorting being a one-way door.
    sortingOrder: ['asc', 'desc', null],
    // DOORS attribute names carry umlauts, and a reviewer may be typing on a keyboard without
    // them — the same reason `shared/text/normalize.ts` exists for the search box.
    accentedSort: true,
  };
}
