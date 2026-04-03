interface IconProps {
  className?: string;
}

/** Bull head — front-facing with curved horns, aggressive silhouette */
export function BullIcon({ className = "w-4 h-4" }: IconProps) {
  return (
    <svg viewBox="0 0 64 64" fill="currentColor" className={className}>
      {/* Left horn */}
      <path d="M14 8C10 4 5 3 2 4c1 3 3 6 6 8l4 4c1 1 2 2 4 2l2-4-4-6z" />
      {/* Right horn */}
      <path d="M50 8c4-4 9-5 12-4-1 3-3 6-6 8l-4 4c-1 1-2 2-4 2l-2-4 4-6z" />
      {/* Head shape */}
      <path d="M16 16c-2 2-4 6-4 11 0 4 1 7 3 10l3 5c1 2 3 4 5 5h2v-4h12v4h2c2-1 4-3 5-5l3-5c2-3 3-6 3-10 0-5-2-9-4-11-3-3-7-5-13-5s-10 2-13 5z" />
      {/* Left ear */}
      <path d="M16 16l-3-2c-1 2-1 4 0 6l3 1v-5z" />
      {/* Right ear */}
      <path d="M48 16l3-2c1 2 1 4 0 6l-3 1v-5z" />
      {/* Left eye */}
      <ellipse cx="24" cy="26" rx="3" ry="2.5" fill="#0a0e17" />
      <ellipse cx="24" cy="25.5" rx="1" ry="1" fill="currentColor" />
      {/* Right eye */}
      <ellipse cx="40" cy="26" rx="3" ry="2.5" fill="#0a0e17" />
      <ellipse cx="40" cy="25.5" rx="1" ry="1" fill="currentColor" />
      {/* Nose/snout */}
      <path d="M26 34c0-1 2-3 6-3s6 2 6 3c0 2-2 4-6 4s-6-2-6-4z" fill="#0a0e17" />
      <ellipse cx="30" cy="33" rx="1.5" ry="1" fill="currentColor" />
      <ellipse cx="34" cy="33" rx="1.5" ry="1" fill="currentColor" />
      {/* Chin tuft */}
      <path d="M28 44l-1 5 2 1 3-2 3 2 2-1-1-5h-8z" />
    </svg>
  );
}

/** Bear head — front-facing with round ears, fur detail, open mouth */
export function BearIcon({ className = "w-4 h-4" }: IconProps) {
  return (
    <svg viewBox="0 0 64 64" fill="currentColor" className={className}>
      {/* Left ear */}
      <circle cx="12" cy="12" r="9" />
      <circle cx="12" cy="12" r="5" fill="#0a0e17" />
      {/* Right ear */}
      <circle cx="52" cy="12" r="9" />
      <circle cx="52" cy="12" r="5" fill="#0a0e17" />
      {/* Head — broad rounded shape with fur */}
      <path d="M8 20c-2 4-3 8-3 13 0 6 2 11 5 14l4 5c2 2 5 4 8 5h2l1-2h14l1 2h2c3-1 6-3 8-5l4-5c3-3 5-8 5-14 0-5-1-9-3-13-3-5-8-9-15-10h-2c-1-1-3-1-6-1s-5 0-6 1h-2c-7 1-12 5-15 10z" />
      {/* Forehead fur V */}
      <path d="M28 18l4 5 4-5" fill="none" stroke="#0a0e17" strokeWidth="1.5" />
      {/* Left eye */}
      <ellipse cx="22" cy="30" rx="4" ry="3.5" fill="#0a0e17" />
      <circle cx="23" cy="29" r="1.2" fill="currentColor" />
      {/* Right eye */}
      <ellipse cx="42" cy="30" rx="4" ry="3.5" fill="#0a0e17" />
      <circle cx="43" cy="29" r="1.2" fill="currentColor" />
      {/* Muzzle */}
      <ellipse cx="32" cy="40" rx="10" ry="8" fill="#0a0e17" opacity="0.3" />
      {/* Nose */}
      <ellipse cx="32" cy="37" rx="4" ry="3" fill="#0a0e17" />
      <ellipse cx="32" cy="36.5" rx="1.5" ry="0.8" fill="currentColor" opacity="0.3" />
      {/* Mouth */}
      <path d="M32 40v4M28 44c2 2 6 2 8 0" fill="none" stroke="#0a0e17" strokeWidth="1.5" strokeLinecap="round" />
      {/* Jaw fur */}
      <path d="M18 42c-1 1-1 3 0 4l3-1-3-3zM46 42c1 1 1 3 0 4l-3-1 3-3z" />
    </svg>
  );
}

/** Seal head — front-facing with round eyes, whiskers, cute expression */
export function SealIcon({ className = "w-4 h-4" }: IconProps) {
  return (
    <svg viewBox="0 0 64 64" fill="currentColor" className={className}>
      {/* Head — rounded oval */}
      <ellipse cx="32" cy="32" rx="22" ry="26" />
      {/* Left eye */}
      <ellipse cx="22" cy="26" rx="4.5" ry="5" fill="#0a0e17" />
      <circle cx="23.5" cy="24.5" r="1.8" fill="currentColor" />
      <circle cx="24" cy="24" r="0.6" fill="#0a0e17" />
      {/* Right eye */}
      <ellipse cx="42" cy="26" rx="4.5" ry="5" fill="#0a0e17" />
      <circle cx="43.5" cy="24.5" r="1.8" fill="currentColor" />
      <circle cx="44" cy="24" r="0.6" fill="#0a0e17" />
      {/* Nose */}
      <ellipse cx="32" cy="36" rx="5" ry="3.5" fill="#0a0e17" />
      <ellipse cx="30" cy="35.5" rx="1.5" ry="1" fill="currentColor" opacity="0.3" />
      <ellipse cx="34" cy="35.5" rx="1.5" ry="1" fill="currentColor" opacity="0.3" />
      {/* Mouth line */}
      <path d="M32 39.5v2.5" fill="none" stroke="#0a0e17" strokeWidth="1.2" />
      <path d="M27 43c2 2 8 2 10 0" fill="none" stroke="#0a0e17" strokeWidth="1.2" strokeLinecap="round" />
      {/* Whiskers — left */}
      <line x1="18" y1="35" x2="4" y2="32" stroke="#0a0e17" strokeWidth="1" />
      <line x1="18" y1="37" x2="3" y2="37" stroke="#0a0e17" strokeWidth="1" />
      <line x1="18" y1="39" x2="4" y2="42" stroke="#0a0e17" strokeWidth="1" />
      {/* Whiskers — right */}
      <line x1="46" y1="35" x2="60" y2="32" stroke="#0a0e17" strokeWidth="1" />
      <line x1="46" y1="37" x2="61" y2="37" stroke="#0a0e17" strokeWidth="1" />
      <line x1="46" y1="39" x2="60" y2="42" stroke="#0a0e17" strokeWidth="1" />
      {/* Spots near whiskers */}
      <circle cx="19" cy="34" r="1" fill="#0a0e17" />
      <circle cx="17" cy="37" r="1" fill="#0a0e17" />
      <circle cx="19" cy="40" r="1" fill="#0a0e17" />
      <circle cx="45" cy="34" r="1" fill="#0a0e17" />
      <circle cx="47" cy="37" r="1" fill="#0a0e17" />
      <circle cx="45" cy="40" r="1" fill="#0a0e17" />
    </svg>
  );
}
