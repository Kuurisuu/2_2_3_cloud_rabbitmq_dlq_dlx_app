const STATES = {
  loading: { label: 'Conectando…', tone: 'neutral' },
  online: { label: 'Backend y RabbitMQ conectados', tone: 'ok' },
  'broker-down': { label: 'RabbitMQ no disponible', tone: 'warn' },
  offline: { label: 'Backend desconectado', tone: 'error' },
};

export default function StatusPill({ connection }) {
  const state = STATES[connection];
  return (
    <p className={`status-pill tone-${state.tone}`} role="status">
      <span className="status-dot" aria-hidden="true" />
      {state.label}
    </p>
  );
}
