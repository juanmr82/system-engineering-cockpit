import { Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { ProblemDetails } from '../../../core/error/problem-details';
import { SEC_MODAL_DIALOG } from '../../../shared/dialog/modal-dialog.config';
import { normalize } from '../../../shared/text/normalize';
import { JiraApiService } from '../jira-api.service';
import type { JiraFieldNode } from '../jira.model';

/** A field and its sub-keys, as the dialog draws them. */
export interface FieldGroup {
  readonly node: JiraFieldNode;
  readonly leaves: JiraFieldNode[];
}

/**
 * The selection state of a group's checkbox.
 *
 * `indeterminate` is what makes a partially selected field readable at a glance — the design doc
 * asks for a tri-state parent (§6.2), and without it a field with one of five sub-keys chosen
 * looks identical to one with none.
 */
export interface GroupState {
  readonly checked: boolean;
  readonly indeterminate: boolean;
}

/**
 * Every selectable path in a group: the field itself when it has a scalar value, plus its leaves.
 *
 * Exported and pure because it is the rule the parent checkbox is built on, and a rule tested
 * through a rendered checkbox is a test of Material.
 */
export function selectablePathsOf(group: FieldGroup): string[] {
  const own = group.node.selectable ? [group.node.path] : [];
  return [...own, ...group.leaves.filter((leaf) => leaf.selectable).map((leaf) => leaf.path)];
}

/**
 * The paths a user can actually turn on and off for this field.
 *
 * Two kinds are excluded and for different reasons: a **fixed** path is always shown, and an
 * **unselectable** one is a field JIRA defines whose sub-keys are not known yet because no issue
 * has a value. A group with none of these left is drawn, and disabled — it says the field exists,
 * which is the information, without offering a column that would be blank for ever.
 */
export function togglablePathsOf(group: FieldGroup): string[] {
  const nodes = [group.node, ...group.leaves];
  return selectablePathsOf(group).filter(
    (path) => !nodes.find((node) => node.path === path)?.fixed,
  );
}

/**
 * What the server says is already chosen.
 *
 * Fixed paths are excluded: they are always shown, and storing them would put the decision in two
 * places — the day the fixed pair changes, stored rows would disagree with the code.
 */
export function initialSelection(fields: JiraFieldNode[]): ReadonlySet<string> {
  const chosen = new Set<string>();
  const walk = (nodes: JiraFieldNode[]) => {
    for (const node of nodes) {
      if (node.selected && !node.fixed) {
        chosen.add(node.path);
      }
      walk(node.children);
    }
  };
  walk(fields);
  return chosen;
}

export function groupStateOf(group: FieldGroup, selected: ReadonlySet<string>): GroupState {
  const paths = selectablePathsOf(group);
  const chosen = paths.filter((path) => selected.has(path)).length;
  return {
    checked: paths.length > 0 && chosen === paths.length,
    indeterminate: chosen > 0 && chosen < paths.length,
  };
}

function extractErrorDetail(error: unknown): string {
  if (error instanceof HttpErrorResponse && error.error) {
    const problem = error.error as Partial<ProblemDetails>;
    if (problem.detail) {
      return problem.detail;
    }
  }
  return 'Those columns could not be saved. Please try again.';
}

/**
 * "Select fields to display" (design doc §6).
 *
 * ## Not a `mat-tree`, and that is a data decision rather than a preference
 *
 * §6.2 suggests `mat-tree`, which is the right tool for arbitrary depth. The data here is exactly
 * two levels — a JIRA field, and the sub-keys the flattener found under it — because a path is
 * split at its first dot and everything after it is one leaf. A tree component for a fixed
 * two-level shape buys a node-data-source abstraction and costs the direct control of the
 * tri-state parent, which is the one interaction that actually matters here.
 *
 * ## The dirty state dies with this dialog (R7)
 *
 * There is no staging layer and no cross-view state: Save posts the absolute ordered list in one
 * request, and a dialog closed without saving has written nothing. On failure it stays open with
 * the selection intact and the error inline — never closed on a failed write, because without a
 * queue there is nothing to recover from.
 */
@Component({
  selector: 'sec-field-selection-dialog',
  imports: [
    MatButtonModule,
    MatCheckboxModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatTooltipModule,
  ],
  templateUrl: './field-selection-dialog.html',
  styleUrl: './field-selection-dialog.scss',
})
export class FieldSelectionDialog {
  private readonly dialogRef = inject(MatDialogRef<FieldSelectionDialog, boolean>);
  private readonly api = inject(JiraApiService);

  protected readonly tree = this.api.fieldTree();
  protected readonly search = signal('');
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  private readonly fields = computed<JiraFieldNode[]>(() =>
    // hasValue() guards the read: `value()` throws in an error state, and an unguarded read in a
    // computed the template consumes tears down the whole dialog.
    this.tree.hasValue() ? this.tree.value().fields : [],
  );

  /**
   * The chosen paths, seeded from the server once the tree arrives.
   *
   * A `linkedSignal` rather than a `signal` written from a constructor: the tree is a request, so
   * there is nothing to seed from at construction time, and seeding from inside a `computed` is a
   * signal write in a computed — which Angular refuses outright. This re-seeds if the tree is
   * reloaded and keeps the user's edits until then, which is exactly a dialog's lifetime.
   */
  protected readonly selected = linkedSignal<JiraFieldNode[], ReadonlySet<string>>({
    source: () => this.fields(),
    computation: (fields) => initialSelection(fields),
  });

  protected readonly groups = computed<FieldGroup[]>(() =>
    this.fields().map((node) => ({ node, leaves: node.children })),
  );

  protected readonly warnings = computed(() =>
    this.tree.hasValue() ? this.tree.value().warnings : [],
  );

  /**
   * Filtered by label and by path.
   *
   * By path as well, because an admin who knows a field as `customfield_10032` should be able to
   * find it by that — the id is what the JIRA administrator's own screens show.
   */
  protected readonly visibleGroups = computed<FieldGroup[]>(() => {
    const term = normalize(this.search().trim());
    if (!term) {
      return this.groups();
    }
    return this.groups().filter((group) => {
      const haystack = [group.node.label, group.node.path, ...group.leaves.map((l) => l.label)];
      return haystack.some((text) => normalize(text).includes(term));
    });
  });

  protected readonly selectedCount = computed(() => this.selected().size);

  protected stateOf(group: FieldGroup): GroupState {
    return groupStateOf(group, this.selected());
  }

  protected isSelected(node: JiraFieldNode): boolean {
    return node.fixed || this.selected().has(node.path);
  }

  protected toggle(node: JiraFieldNode, checked: boolean): void {
    if (node.fixed) {
      return;
    }
    this.selected.update((current) => {
      const next = new Set(current);
      if (checked) {
        next.add(node.path);
      } else {
        next.delete(node.path);
      }
      return next;
    });
  }

  /** False for a field that is defined in JIRA but has nothing selectable under it yet. */
  protected isGroupSelectable(group: FieldGroup): boolean {
    return togglablePathsOf(group).length > 0;
  }

  /** The parent checkbox: all of a field's paths on, or all of them off. */
  protected toggleGroup(group: FieldGroup, checked: boolean): void {
    const paths = togglablePathsOf(group);
    this.selected.update((current) => {
      const next = new Set(current);
      for (const path of paths) {
        if (checked) {
          next.add(path);
        } else {
          next.delete(path);
        }
      }
      return next;
    });
  }

  protected clearAll(): void {
    this.selected.set(new Set());
  }

  protected async save(): Promise<void> {
    if (this.saving()) {
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    try {
      // The order the tree lists them in. A column order the admin drags is a separate feature;
      // the server stores a position either way, so adding it later changes no stored shape.
      const ordered = this.groups()
        .flatMap((group) => selectablePathsOf(group))
        .filter((path) => this.selected().has(path));
      await this.api.saveColumns({ paths: ordered });
      this.dialogRef.close(true);
    } catch (error) {
      // Stays open, with the selection intact (R7).
      this.saveError.set(extractErrorDetail(error));
    } finally {
      this.saving.set(false);
    }
  }

  protected cancel(): void {
    this.dialogRef.close(false);
  }

  static open(dialog: MatDialog) {
    return dialog.open<FieldSelectionDialog, undefined, boolean>(FieldSelectionDialog, {
      ...SEC_MODAL_DIALOG,
      width: '720px',
      height: '640px',
    });
  }
}
