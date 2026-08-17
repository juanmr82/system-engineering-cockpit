import { describe, expect, it } from 'vitest';
import { parseMentions } from './mentions.model';

describe('parseMentions', () => {
  it('returns a single text segment for plain text', () => {
    expect(parseMentions('no mentions here')).toEqual([{ kind: 'text', value: 'no mentions here' }]);
  });

  it('parses a lone mention with nothing around it', () => {
    expect(parseMentions('@[REQ-1042](item:UkVRLTEwNDI)')).toEqual([
      { kind: 'mention', display: 'REQ-1042', target: 'item', ref: 'UkVRLTEwNDI' },
    ]);
  });

  it('splits text and mentions in order, both kinds', () => {
    expect(parseMentions('see @[Elena K.](user:sub-1) re @[REQ-1](item:ref-1) please')).toEqual([
      { kind: 'text', value: 'see ' },
      { kind: 'mention', display: 'Elena K.', target: 'user', ref: 'sub-1' },
      { kind: 'text', value: ' re ' },
      { kind: 'mention', display: 'REQ-1', target: 'item', ref: 'ref-1' },
      { kind: 'text', value: ' please' },
    ]);
  });

  it('leaves an unknown kind as plain text', () => {
    const text = '@[X](ticket:123)';
    expect(parseMentions(text)).toEqual([{ kind: 'text', value: text }]);
  });

  it('returns nothing for an empty string', () => {
    expect(parseMentions('')).toEqual([]);
  });
});
