interface IconProps {
  className?: string;
}

/** Bull silhouette — charging bull facing right */
export function BullIcon({ className = "w-4 h-4" }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className={className}>
      <path d="M2 8c1-3 2-4 4-5l1 3c1-1 3-1 4 0l2-3c2 0 3.5 1 4 3h2l1-2.5c.5 1.5.5 3-1 4.5l-1 1v3c0 2-1 3-2 4h-2v-2h-1v2H9v-2H8v2H6c-1-1-2-2-2-4v-3l-1-1C1.5 9 1.5 8 2 8z" />
    </svg>
  );
}

/** Bear silhouette — standing bear facing right */
export function BearIcon({ className = "w-4 h-4" }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className={className}>
      <circle cx="7" cy="4" r="2.5" />
      <circle cx="17" cy="4" r="2.5" />
      <path d="M6 6c-1 1-2 3-2 5v4c0 1 .5 2 1 3l1 2h2l.5-2h7l.5 2h2l1-2c.5-1 1-2 1-3v-4c0-2-1-4-2-5-1.5-1.5-3-2-5-2S7.5 4.5 6 6z" />
      <circle cx="10" cy="10" r="1" fill="#0a0e17" />
      <circle cx="14" cy="10" r="1" fill="#0a0e17" />
      <ellipse cx="12" cy="12.5" rx="1.5" ry="1" fill="#0a0e17" />
    </svg>
  );
}

/** Seal silhouette — resting seal facing right */
export function SealIcon({ className = "w-4 h-4" }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className={className}>
      <path d="M3 16c0-2 1-4 3-5 1-.5 2-1 4-1h2c2 0 3 0 4 .5 2 .8 3 2 4 4l1 2c.3.5 0 1-.5 1h-1l-2-1c-1 0-2 .5-3 1l-2 1c-1 .3-2 .3-3 0l-2-1c-1-.5-2-1-3-1.5-.5-.3-1-.5-1-1z" />
      <circle cx="7.5" cy="12" r="3.5" />
      <circle cx="6.5" cy="11.5" r=".7" fill="#0a0e17" />
      <circle cx="8.5" cy="11.5" r=".7" fill="#0a0e17" />
      <ellipse cx="7.5" cy="13" rx="1" ry=".5" fill="#0a0e17" />
      <path d="M3 12.5c-1-.5-2-2-1.5-3s1.5-.5 2 0" fill="currentColor" />
    </svg>
  );
}
