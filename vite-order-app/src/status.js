export const STATUS = {
  ENVIADA: { label: 'Enviada', icon: '→', group: 'active' },
  PROCESANDO: { label: 'Procesando', icon: null, group: 'active' },
  REINTENTANDO: { label: 'Reintentando', icon: '↻', group: 'active' },
  PROCESADA: { label: 'Procesada', icon: '✓', group: 'done' },
  EN_DLQ: { label: 'En DLQ', icon: '✕', group: 'dlq' },
};

export function deadLetterReason(reason, ttlMs) {
  switch (reason) {
    case 'rejected':
      return 'Rechazada tras agotar los reintentos';
    case 'expired':
      return `Expiró por TTL: pasó ${Math.round(ttlMs / 1000)} s en cola sin procesarse`;
    case 'maxlen':
      return 'Descartada porque la cola superó x-max-length';
    case 'delivery_limit':
      return 'Superó el límite de entregas de la cola';
    default:
      return `Motivo: ${reason ?? 'desconocido'}`;
  }
}

export function formatTime(iso) {
  return new Date(iso).toLocaleTimeString('es', { hour12: false });
}
