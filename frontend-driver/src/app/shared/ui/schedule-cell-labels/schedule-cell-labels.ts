import {AfterViewInit, Directive, ElementRef, OnDestroy, Renderer2, inject} from '@angular/core';

const PENDING_HINT = '請假申請中，等待主管審核；暫不可重複申請。';

const SHIFT_LABELS = [
  {className: 'shift-work', label: '上班', name: '上班', path: 'M20 6h-4V4c0-1.1-.9-2-2-2h-4C8.9 2 8 2.9 8 4v2H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2ZM10 4h4v2h-4Zm10 16H4v-6h7v2h2v-2h7Zm0-8H4V8h16Z'},
  {className: 'shift-day_off', label: '休假', name: '休假', path: 'M18 3H2v9c0 3.31 2.69 6 6 6h4c3.31 0 6-2.69 6-6V9h1c1.66 0 3-1.34 3-3s-1.34-3-3-3h-1Zm-2 9c0 2.21-1.79 4-4 4H8c-2.21 0-4-1.79-4-4V5h12Zm3-5h-1V5h1a1 1 0 0 1 0 2ZM2 20h18v2H2Z'},
  {className: 'shift-leave', label: '請假', name: '請假', path: 'M19 4h-1V2h-2v2H8V2H6v2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2Zm0 16H5V10h14ZM9.4 11 12 13.6l2.6-2.6 1.4 1.4-2.6 2.6 2.6 2.6-1.4 1.4-2.6-2.6L9.4 19 8 17.6l2.6-2.6L8 12.4Z'},
  {className: 'shift-unassigned', label: '未排', name: '未排班', path: ''},
  {className: 'shift-not-published', label: '無班表', name: '無已發布班表', path: ''},
  {className: 'shift-loading', label: '載入中', name: '班表載入中', path: ''},
  {className: 'shift-unavailable', label: '未載入', name: '班表載入失敗', path: ''},
];

/** Decorate only month-date cells; retain Material's date text, navigation and keyboard behavior. */
@Directive({selector: 'mat-calendar[appScheduleCellLabels]'})
export class ScheduleCellLabels implements AfterViewInit, OnDestroy {
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly renderer = inject(Renderer2);
  private observer?: MutationObserver;

  ngAfterViewInit(): void {
    const Observer = this.element.nativeElement.ownerDocument.defaultView?.MutationObserver;
    if (Observer) this.observer = new Observer(() => this.decorate());
    this.decorate();
  }

  ngOnDestroy(): void { this.observer?.disconnect(); }

  private decorate(): void {
    // Ignore our own decorations, so adding badges/accessible labels cannot trigger a loop.
    this.observer?.disconnect();
    const host = this.element.nativeElement;
    for (const cell of host.querySelectorAll<HTMLElement>('mat-month-view .mat-calendar-body-cell')) {
      const shift = SHIFT_LABELS.find(item => cell.classList.contains(item.className));
      const content = cell.querySelector('.mat-calendar-body-cell-content');
      if (!shift || !content) continue;
      const pending = cell.classList.contains('leave-request-pending');
      if (pending) this.renderer.setAttribute(cell, 'title', PENDING_HINT);
      else if (cell.getAttribute('title') === PENDING_HINT) this.renderer.removeAttribute(cell, 'title');
      const pendingLabel = content.querySelector('.schedule-cell-pending');
      if (pending && !pendingLabel) {
        const notice = this.renderer.createElement('span');
        this.renderer.addClass(notice, 'schedule-cell-pending');
        this.renderer.setAttribute(notice, 'aria-hidden', 'true');
        this.renderer.appendChild(notice, this.renderer.createText('申請中'));
        this.renderer.appendChild(content, notice);
      } else if (!pending && pendingLabel) {
        this.renderer.removeChild(content, pendingLabel);
      }
      const previous = content.querySelector<HTMLElement>('.schedule-cell-shift');
      if (previous?.dataset['shift'] !== shift.className) {
        if (previous) this.renderer.removeChild(content, previous);
        const badge = this.renderer.createElement('span');
        this.renderer.addClass(badge, 'schedule-cell-shift');
        this.renderer.setAttribute(badge, 'data-shift', shift.className);
        this.renderer.setAttribute(badge, 'aria-hidden', 'true');
        if (shift.path) {
          const icon = this.renderer.createElement('svg', 'svg');
          this.renderer.setAttribute(icon, 'viewBox', '0 0 24 24');
          this.renderer.setAttribute(icon, 'focusable', 'false');
          const path = this.renderer.createElement('path', 'svg');
          this.renderer.setAttribute(path, 'd', shift.path);
          this.renderer.appendChild(icon, path);
          this.renderer.appendChild(badge, icon);
        }
        this.renderer.appendChild(badge, this.renderer.createText(shift.label));
        this.renderer.insertBefore(content, badge, content.querySelector('.schedule-cell-pending'));
      }
      // Native date aria-label is rewritten by Material on rerender; never lose the date.
      const base = (cell.getAttribute('aria-label') ?? '').replace(/，(?:上班|休假|請假|未排班|無已發布班表|班表載入中|班表載入失敗)(?:，已選請假日期)?(?:，申請中(?:，等待主管審核)?，不可重複申請)?$/, '');
      const leaveSelected = cell.classList.contains('leave-date-selected');
      const label = `${base}，${shift.name}${leaveSelected ? '，已選請假日期' : ''}${pending ? '，申請中，等待主管審核，不可重複申請' : ''}`;
      if (cell.getAttribute('aria-label') !== label) this.renderer.setAttribute(cell, 'aria-label', label);
      this.renderer.setAttribute(cell, 'aria-pressed', String(leaveSelected || content.classList.contains('mat-calendar-body-selected')));
    }
    this.observer?.observe(host, {subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'aria-label']});
  }
}
