import { Info } from 'lucide-react';
import { OPTION_TOOLTIPS, type OptionTooltipKey } from '@/lib/optionsTooltips';
import { cn } from '@/lib/utils';

interface LabelHintProps {
    label: string;
    tip: OptionTooltipKey;
    className?: string;
}

/**
 * Small label + info-icon. Hovering the whole pair shows the native
 * browser tooltip (no JS, accessible, dirt-cheap). The icon is a
 * non-decorative cue that explanation exists; without it users wouldn't
 * know to hover.
 */
export function LabelHint({ label, tip, className }: LabelHintProps) {
    return (
        <span
            className={cn(
                'inline-flex items-center gap-0.5 cursor-help text-text-secondary',
                className,
            )}
            title={OPTION_TOOLTIPS[tip]}
        >
            <span>{label}</span>
            <Info className="h-2.5 w-2.5 opacity-40" aria-hidden="true" />
        </span>
    );
}
