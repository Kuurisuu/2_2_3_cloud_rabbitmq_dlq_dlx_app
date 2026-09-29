export default function Toasts({ toasts, onDismiss }) {
  return (
    <div className="toasts" aria-live="polite" aria-label="Alertas">
      {toasts.map((toast) => (
        <div key={toast.id} className={`toast tone-${toast.tone} ${toast.leaving ? 'leaving' : ''}`}>
          <div className="toast-body">
            <strong>{toast.title}</strong>
            <span>{toast.text}</span>
          </div>
          <button
            type="button"
            className="toast-close"
            onClick={() => onDismiss(toast.id)}
            aria-label={`Cerrar alerta: ${toast.title}`}
          >
            ✕
          </button>
        </div>
      ))}
    </div>
  );
}
