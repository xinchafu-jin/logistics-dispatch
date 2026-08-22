import { Component, ElementRef, OnDestroy, OnInit, ViewChild } from '@angular/core';

@Component({
  selector: 'app-electric-gaze-visual',
  imports: [],
  templateUrl: './electric-gaze-visual.html',
  styleUrl: './electric-gaze-visual.scss',
})
export class ElectricGazeVisual implements OnInit, OnDestroy {
  protected showVisual = false;

  private mediaQuery?: MediaQueryList;

  @ViewChild('visual')
  set visual(video: ElementRef<HTMLVideoElement> | undefined) {
    if (video) {
      void video.nativeElement.play().catch(() => undefined);
    }
  }

  private readonly handleViewportChange = () => {
    this.showVisual = this.mediaQuery?.matches ?? false;
  };

  ngOnInit(): void {
    this.mediaQuery = window.matchMedia('(min-width: 821px)');
    this.handleViewportChange();
    this.mediaQuery.addEventListener('change', this.handleViewportChange);
  }

  ngOnDestroy(): void {
    this.mediaQuery?.removeEventListener('change', this.handleViewportChange);
  }
}
