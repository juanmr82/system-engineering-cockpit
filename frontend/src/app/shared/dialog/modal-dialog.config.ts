import { MatDialogConfig } from '@angular/material/dialog';

// The modal contract from CLAUDE.md R7. A dialog is the only place unsaved state can exist, so it
// must not be dismissable by ESC or a backdrop click — Save and Cancel are the only exits, and a
// dismissable dialog would silently discard a user's edits.
//
// Spread this into every dialog's own config; never re-declare disableClose per call site, and
// never override it to false. Dialogs are also not draggable, minimisable or resizable — that is
// Material's default and no cdkDrag is to be added (CLAUDE.md §6).
//
// `satisfies` keeps the literal types (autoFocus stays 'first-tabbable', not string) while still
// type-checking the shape against MatDialogConfig.
export const SEC_MODAL_DIALOG = {
  disableClose: true,
  autoFocus: 'first-tabbable',
  restoreFocus: true,
} as const satisfies MatDialogConfig;
