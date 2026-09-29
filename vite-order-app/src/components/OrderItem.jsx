import { STATUS, deadLetterReason, formatTime } from '../status.js';

export default function OrderItem({ order, maxAttempts, ttlMs }) {
  const meta = STATUS[order.status];

  return (
    <li className={`order-item status-${order.status}`}>
      <div className="order-header">
        <strong className="order-id">{order.id}</strong>
        <span className={`badge badge-${order.status}`}>
          {meta.icon ? (
            <span aria-hidden="true">{meta.icon}</span>
          ) : (
            <span className="spinner" aria-hidden="true" />
          )}
          {meta.label}
        </span>
      </div>

      <div className="order-detail">
        <span>Cliente: {order.customer}</span>
        <time dateTime={order.createdAt}>{formatTime(order.createdAt)}</time>
      </div>

      <div className="order-footer">
        <span className="attempts" aria-label={`Intentos: ${order.attempts} de ${maxAttempts}`}>
          {Array.from({ length: maxAttempts }, (_, i) => (
            <span key={i} className={`attempt-dot ${i < order.attempts ? 'used' : ''}`} aria-hidden="true" />
          ))}
          <span aria-hidden="true">
            {order.attempts}/{maxAttempts} intentos
          </span>
        </span>

        {order.status === 'EN_DLQ' && (
          <span className="order-reason">{deadLetterReason(order.deadLetterReason, ttlMs)}</span>
        )}
        {order.status === 'REINTENTANDO' && order.lastError && (
          <span className="order-reason retry">{order.lastError}</span>
        )}
      </div>
    </li>
  );
}
