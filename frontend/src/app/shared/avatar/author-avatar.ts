import { Component, computed, input } from '@angular/core';
import { avatarColorOf, initialsOf } from '../text/initials';

/**
 * A comment author's initials, in a small outlined chip — the "TS" / "JR" boxes from the review
 * table's own design baseline. Used by the Comment column's compact chip and by the thread panel's
 * note headers, so a reviewer learns one shape for "who wrote this" across both.
 */
@Component({
  selector: 'sec-author-avatar',
  templateUrl: './author-avatar.html',
  styleUrl: './author-avatar.scss',
})
export class AuthorAvatar {
  readonly name = input.required<string>();

  protected readonly initials = computed(() => initialsOf(this.name()));
  protected readonly color = computed(() => avatarColorOf(this.name()));
}
