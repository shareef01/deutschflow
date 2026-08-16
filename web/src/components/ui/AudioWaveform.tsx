"use client";

import { useEffect, useRef } from "react";

/**
 * AudioWaveform — ui/components/AudioWaveform.kt port.
 *
 * A live input-level meter drawn entirely in the draw phase: `getLevel` is read
 * inside a requestAnimationFrame loop, never during React rendering, so a
 * ~12 Hz recognizer level costs zero recompositions. Each bar's height is a
 * fixed sine envelope scaled by the instantaneous level — enough to read as a
 * voice without a per-bar ring buffer.
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

    const resize = () => {
      const dpr = window.devicePixelRatio || 1;
      canvas.width = Math.max(1, canvas.clientWidth * dpr);
      canvas.height = Math.max(1, canvas.clientHeight * dpr);
      context.setTransform(dpr, 0, 0, dpr, 0, 0);
    };
    resize();
    const observer = new ResizeObserver(resize);
    observer.observe(canvas);

    let frame = 0;
    const draw = () => {
      const level = activeRef.current ? Math.min(1, Math.max(0, getLevelRef.current())) : 0;
      const { width, height } = canvas;
      context.clearRect(0, 0, width, height);

      if (bars > 1) {
        const slot = width / (bars * 2 - 1);
        const barWidth = slot;
        context.fillStyle = `rgba(0, 229, 255, ${0.3 + 0.7 * level})`;
        for (let i = 0; i < bars; i++) {
          // Idle, the meter collapses to a faint static comb so the card reads
          // as reserved rather than empty.
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
      }

      frame = requestAnimationFrame(draw);
    };
    draw();

    return () => {
      cancelAnimationFrame(frame);
      observer.disconnect();
    };
  }, [bars]);

  return <canvas ref={canvasRef} className={className} aria-hidden="true" />;
}
