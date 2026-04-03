import { CorrelationMatrixPanel } from './CorrelationMatrix';
import { VolatilityDashboard } from './VolatilityDashboard';
import { MacroIndicators } from './MacroIndicators';

export function AnalyticsDashboard() {
  return (
    <div className="space-y-6">
      <MacroIndicators />
      <CorrelationMatrixPanel />
      <VolatilityDashboard />
    </div>
  );
}
