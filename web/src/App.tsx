// PRD-01 placeholder. The real dashboard (browser matrix, live SSE, workers) is PRD-05.
// It deliberately shows no run data: no fake metrics are presented as real (AGENTS.md).
export function App() {
  return (
    <main
      style={{
        fontFamily: 'system-ui, sans-serif',
        maxWidth: 640,
        margin: '48px auto',
        padding: 16,
      }}
    >
      <h1 data-testid="web-title">TestGrid</h1>
      <p data-testid="web-placeholder">
        Dashboard placeholder. Runs, targets and live status arrive in PRD-05.
      </p>
    </main>
  );
}
