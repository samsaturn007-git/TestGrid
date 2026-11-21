import { useEffect, useState } from 'react';

// Times are shown relative ("13 min ago") with the exact UTC time in a <time> element, so the
// relative text can be approximate without hiding the real value.

export function formatAgo(iso: string, now: number): string {
  const seconds = Math.max(0, Math.round((now - Date.parse(iso)) / 1000));
  if (seconds < 60) return `${seconds} s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24)
    return minutes % 60 === 0 ? `${hours} h ago` : `${hours} h ${minutes % 60} min ago`;
  const days = Math.floor(hours / 24);
  return days === 1 ? '1 day ago' : `${days} days ago`;
}

/** "Nov 20, 2026, 12:04:22 UTC": the same for every viewer, whatever their time zone. */
export function formatAbsolute(iso: string): string {
  return `${new Intl.DateTimeFormat('en-US', {
    timeZone: 'UTC',
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).format(new Date(iso))} UTC`;
}

export function formatDuration(ms: number): string {
  const total = Math.max(0, ms);
  if (total < 60_000) return `${(total / 1000).toFixed(1)} s`;
  const seconds = Math.floor(total / 1000);
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min ${String(seconds % 60).padStart(2, '0')} s`;
  return `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, '0')} min`;
}

/** The current time, refreshed every `intervalMs`, for relative times that age on screen. */
export function useNow(intervalMs = 30_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);
  return now;
}
