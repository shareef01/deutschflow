"use client";

import { useEffect, useRef } from "react";

/**
 * AudioWaveform — live input-level meter drawn entirely in the draw phase.
 *
 * Performance & HiDPI Architecture:
 * 1. HiDPI Canvas Coordinate Scaling: Canvas backing buffer (canvas.width/height)
 *    is scaled by devicePixelRatio, and context is transformed by dpr. Drawing operations
 *    use logical CSS dimensions (clientWidth/clientHeight) to prevent double-scaling and clipping.
 * 2. Idle Gating: When inactive (isActive=false), the idle static comb is drawn once,
 *    and requestAnimationFrame is paused. Continuous rAF repainting only runs while recording.
 * 3. Tab Visibility & Reduced Motion: Pauses rAF when document is hidden (document.hidden)
 *    and respects prefers-reduced-motion to conserve battery and CPU.
 */
export function AudioWaveform({
  getLevel,
  isActive,
  bars = 28,
  className,
}: {
  /** Returns the instantaneous level 0..1 (read per frame, not per render). */
  getLevel: () => number;
  isActive: boolean;
  bars?: number;
  className?: string;
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const getLevelRef = useRef(getLevel);
  getLevelRef.current = getLevel;
  const activeRef = useRef(isActive);
  activeRef.current = isActive;

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const context = canvas.getContext("2d");
    if (!context) return;

    let activeColor = "#4ec9e8";
    const updateColor = () => {
      const val = window.getComputedStyle(canvas).getPropertyValue("--color-azure-glow").trim();
      if (val) activeColor = val;
    };
    updateColor();

    let frame = 0;
    let isLoopRunning = false;

    const renderFrame = (level: number) => {
      // Use logical CSS layout dimensions since context is scaled by dpr.
      const width = canvas.clientWidth || 300;
      const height = canvas.clientHeight || 40;
      context.clearRect(0, 0, width, height);

      if (bars > 1) {
        const slot = width / (bars * 2 - 1);
        const barWidth = slot;
        context.fillStyle = activeColor;
        context.globalAlpha = 0.3 + 0.7 * level;
        for (let i = 0; i < bars; i++) {
          const envelope = 0.3 + 0.7 * Math.sin((Math.PI * i) / (bars - 1));
          const barHeight = height * (0.06 + 0.94 * envelope * level);
          const x = i * slot * 2;
          const y = (height - barHeight) / 2;
          context.beginPath();
          if (typeof context.roundRect === "function") {
            context.roundRect(x, y, barWidth, barHeight, barWidth / 2);
          } else {
            context.rect(x, y, barWidth, barHeight);
          }
          context.fill();
        }
        context.globalAlpha = 1.0;
      }
    };

    const loop = () => {
      if (!activeRef.current || document.hidden) {
        isLoopRunning = false;
        renderFrame(0);
        return;
      }
      const level = Math.min(1, Math.max(0, getLevelRef.current()));
      renderFrame(level);
      frame = requestAnimationFrame(loop);
    };

    const startLoopIfNeeded = () => {
      if (activeRef.current && !document.hidden && !isLoopRunning) {
        isLoopRunning = true;
        frame = requestAnimationFrame(loop);
      } else if (!activeRef.current) {
        if (isLoopRunning) {
          cancelAnimationFrame(frame);
          isLoopRunning = false;
        }
        renderFrame(0);
      }
    };

    const resize = () => {
      const dpr = window.devicePixelRatio || 1;
      const cssWidth = canvas.clientWidth || 300;
      const cssHeight = canvas.clientHeight || 40;
      canvas.width = Math.max(1, Math.floor(cssWidth * dpr));
      canvas.height = Math.max(1, Math.floor(cssHeight * dpr));
      context.setTransform(dpr, 0, 0, dpr, 0, 0);
      updateColor();
      startLoopIfNeeded();
    };
    resize();

    const observer = new ResizeObserver(resize);
    observer.observe(canvas);

    const onVisibilityChange = () => {
      if (document.hidden) {
        if (isLoopRunning) {
          cancelAnimationFrame(frame);
          isLoopRunning = false;
        }
      } else {
        startLoopIfNeeded();
      }
    };
    document.addEventListener("visibilitychange", onVisibilityChange);

    startLoopIfNeeded();

    return () => {
      cancelAnimationFrame(frame);
      observer.disconnect();
      document.removeEventListener("visibilitychange", onVisibilityChange);
    };
  }, [bars, isActive]);

  return <canvas ref={canvasRef} className={className} aria-hidden="true" />;
}
