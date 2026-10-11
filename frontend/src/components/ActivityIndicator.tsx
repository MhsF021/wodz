export function ActivityIndicator({ active }: { active: boolean }) {
  if (!active) return null;
  return (
    <div className="activity-indicator" role="status" aria-label="Carregando">
      <span className="spinner" aria-hidden />
    </div>
  );
}
