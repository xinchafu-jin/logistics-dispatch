/** Enter 送出聊天訊息；Shift+Enter 換行，輸入法選字時不送出。 */
export function shouldSubmitChatOnEnter(event: KeyboardEvent): boolean {
  return event.key === 'Enter'
    && !event.shiftKey
    && !event.ctrlKey
    && !event.altKey
    && !event.metaKey
    && !event.isComposing
    && event.keyCode !== 229;
}
