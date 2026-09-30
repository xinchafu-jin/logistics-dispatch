import {describe, expect, it} from 'vitest';
import {shouldSubmitChatOnEnter} from './chat-enter';

describe('chat Enter shortcut', () => {
  it('sends with plain Enter', () => {
    expect(shouldSubmitChatOnEnter(new KeyboardEvent('keydown', {key: 'Enter'}))).toBe(true);
  });

  it('keeps Shift+Enter for a newline', () => {
    expect(shouldSubmitChatOnEnter(new KeyboardEvent('keydown', {key: 'Enter', shiftKey: true}))).toBe(false);
  });

  it('does not send while an input method is choosing characters', () => {
    expect(shouldSubmitChatOnEnter(new KeyboardEvent('keydown', {key: 'Enter', isComposing: true}))).toBe(false);
    const legacyComposition = new KeyboardEvent('keydown', {key: 'Enter'});
    Object.defineProperty(legacyComposition, 'keyCode', {value: 229});
    expect(shouldSubmitChatOnEnter(legacyComposition)).toBe(false);
  });

  it('ignores other keys and modified Enter', () => {
    expect(shouldSubmitChatOnEnter(new KeyboardEvent('keydown', {key: 'a'}))).toBe(false);
    expect(shouldSubmitChatOnEnter(new KeyboardEvent('keydown', {key: 'Enter', ctrlKey: true}))).toBe(false);
  });
});
