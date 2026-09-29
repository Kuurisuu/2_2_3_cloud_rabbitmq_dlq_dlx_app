import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from './api.js';
import { STATUS, deadLetterReason } from './status.js';
import StatusPill from './components/StatusPill.jsx';
import OrderItem from './components/OrderItem.jsx';
import Toasts from './components/Toasts.jsx';

const POLL_MS = 1000;
const DLQ_ALERT_THRESHOLD = 5;
const MANAGEMENT_DLQ_URL = 'http://localhost:15672/#/queues/%2F/orders.dlq';

const CUSTOMERS = [
  { label: 'Orden cliente 1', name: 'Juan Pérez' },
  { label: 'Orden cliente 2', name: 'María García' },
  { label: 'Orden cliente 3', name: 'Carlos López' },
];

const FILTERS = [
  { id: 'all', label: 'Todas' },
  { id: 'active', label: 'En curso' },
  { id: 'done', label: 'Procesadas' },
  { id: 'dlq', label: 'En DLQ' },
];

const DEFAULT_CONFIG = { maxAttempts: 3, ordersTtlMs: 30000, failureRate: 0.5 };

let toastSeq = 0;

export default function App() {
  const [data, setData] = useState(null);
  const [connection, setConnection] = useState('loading');
  const [toasts, setToasts] = useState([]);
  const [filter, setFilter] = useState('all');
  const knownStatuses = useRef(null);

  const dismissToast = useCallback((id) => {
    setToasts((prev) => prev.map((t) => (t.id === id ? { ...t, leaving: true } : t)));
    setTimeout(() => setToasts((prev) => prev.filter((t) => t.id !== id)), 250);
  }, []);

  const pushToast = useCallback(
    (tone, title, text) => {
      const id = ++toastSeq;
      setToasts((prev) => [...prev.slice(-4), { id, tone, title, text }]);
      setTimeout(() => dismissToast(id), tone === 'error' ? 8000 : 5000);
    },
    [dismissToast],
  );

  const load = useCallback(
    async (signal) => {
      try {
        const next = await api.dashboard(signal);
        setData(next);
        setConnection(next.queues.length > 0 ? 'online' : 'broker-down');

        const previous = knownStatuses.current;
        if (previous) {
          next.orders
            .filter((o) => o.status === 'EN_DLQ' && previous.get(o.id) !== 'EN_DLQ')
            .forEach((o) =>
              pushToast('error', `${o.id} enviada a la DLQ`, deadLetterReason(o.deadLetterReason, next.config.ordersTtlMs)),
            );
        }
        knownStatuses.current = new Map(next.orders.map((o) => [o.id, o.status]));
      } catch (error) {
        if (error.name !== 'AbortError') setConnection('offline');
      }
    },
    [pushToast],
  );

  useEffect(() => {
    const controller = new AbortController();
    let timer;
    const tick = async () => {
      await load(controller.signal);
      if (!controller.signal.aborted) timer = setTimeout(tick, POLL_MS);
    };
    tick();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [load]);

  const run = async (action, successTitle) => {
    try {
      const result = await action();
      if (successTitle) pushToast('info', successTitle, result.orderId ?? `${result.count} órdenes publicadas`);
      await load();
    } catch (error) {
      pushToast('error', 'La operación falló', error.message);
    }
  };

  const config = data?.config ?? DEFAULT_CONFIG;
  const stats = data?.stats ?? { sent: 0, processed: 0, inFlight: 0, deadLettered: 0 };
  const orders = data?.orders ?? [];
  const visibleOrders = filter === 'all' ? orders : orders.filter((o) => STATUS[o.status].group === filter);
  const queue = (name) => data?.queues.find((q) => q.name === name);
  const dlqDepth = queue('orders.dlq')?.messages ?? 0;
  const disabled = connection !== 'online';

  return (
    <>
      <a className="skip-link" href="#ordenes">
        Saltar al historial de órdenes
      </a>

      <header className="topbar">
        <div className="topbar-inner">
          <div className="brand">
            <span className="brand-mark" aria-hidden="true" />
            <div>
              <h1>Sistema de órdenes</h1>
              <p className="brand-sub">RabbitMQ con reintentos, DLX/DLQ y TTL</p>
            </div>
          </div>
          <StatusPill connection={connection} />
        </div>
      </header>

      <main className="layout">
        {dlqDepth >= DLQ_ALERT_THRESHOLD && (
          <div className="banner" role="alert">
            <span className="banner-icon" aria-hidden="true">!</span>
            <p>
              La DLQ acumula <strong>{dlqDepth} mensajes</strong>. Revisa la causa de los fallos antes de
              reprocesarlos.{' '}
              <a href={MANAGEMENT_DLQ_URL} target="_blank" rel="noreferrer">
                Abrir orders.dlq en RabbitMQ
              </a>
            </p>
          </div>
        )}

        <section className="stats" aria-label="Resumen">
          <StatCard label="Total enviadas" value={stats.sent} />
          <StatCard label="Procesadas" value={stats.processed} tone="ok" />
          <StatCard label="En curso" value={stats.inFlight} tone="warn" />
          <StatCard label="En DLQ" value={stats.deadLettered} tone="error" />
        </section>

        <div className="columns">
          <aside className="side">
            <section className="panel" aria-labelledby="enviar-title">
              <h2 id="enviar-title">Enviar órdenes</h2>
              <p className="panel-hint">
                Cada intento falla con un {Math.round(config.failureRate * 100)} % de probabilidad. Tras{' '}
                {config.maxAttempts} intentos fallidos la orden termina en la DLQ.
              </p>

              <div className="button-grid">
                {CUSTOMERS.map((c) => (
                  <button
                    key={c.name}
                    type="button"
                    className="btn btn-primary"
                    disabled={disabled}
                    onClick={() => run(() => api.send(c.name), 'Orden publicada')}
                  >
                    <span>{c.label}</span>
                    <small>{c.name}</small>
                  </button>
                ))}
              </div>

              <h3 className="subheading">Escenarios de prueba</h3>
              <div className="button-grid">
                <button
                  type="button"
                  className="btn btn-danger"
                  disabled={disabled}
                  onClick={() => run(() => api.send('Cliente defectuoso', true), 'Orden defectuosa publicada')}
                >
                  <span>Orden defectuosa</span>
                  <small>Falla siempre y termina en la DLQ</small>
                </button>
                <button
                  type="button"
                  className="btn btn-secondary"
                  disabled={disabled}
                  onClick={() => run(() => api.burst(20), 'Ráfaga enviada')}
                >
                  <span>Ráfaga de 20</span>
                  <small>Satura la cola para ver expirar el TTL</small>
                </button>
              </div>
            </section>

            <section className="panel" aria-labelledby="colas-title">
              <h2 id="colas-title">Colas en RabbitMQ</h2>
              <dl className="queues">
                <QueueRow
                  name="orders.queue"
                  info={queue('orders.queue')}
                  detail={`TTL ${Math.round(config.ordersTtlMs / 1000)} s · máx. 1000 mensajes`}
                />
                <QueueRow name="orders.dlq" info={queue('orders.dlq')} detail="TTL 24 h · auditoría" alert={dlqDepth > 0} />
              </dl>
              <a className="external" href="http://localhost:15672" target="_blank" rel="noreferrer">
                Abrir RabbitMQ Management
              </a>
            </section>
          </aside>

          <section className="panel orders" id="ordenes" aria-labelledby="historial-title" tabIndex={-1}>
            <div className="orders-head">
              <h2 id="historial-title">Historial de órdenes</h2>
              <button
                type="button"
                className="btn-ghost"
                onClick={() => run(api.clear)}
                disabled={orders.length === 0 || connection === 'offline'}
              >
                Limpiar historial
              </button>
            </div>

            <div className="filters" role="group" aria-label="Filtrar órdenes">
              {FILTERS.map((f) => (
                <button
                  key={f.id}
                  type="button"
                  className="filter"
                  aria-pressed={filter === f.id}
                  onClick={() => setFilter(f.id)}
                >
                  {f.label}
                </button>
              ))}
            </div>

            {visibleOrders.length === 0 ? (
              <p className="empty">
                {orders.length === 0 ? 'No hay órdenes aún. Envía una para empezar.' : 'Ninguna orden en este filtro.'}
              </p>
            ) : (
              <ul className="order-list">
                {visibleOrders.map((order) => (
                  <OrderItem key={order.id} order={order} maxAttempts={config.maxAttempts} ttlMs={config.ordersTtlMs} />
                ))}
              </ul>
            )}
          </section>
        </div>
      </main>

      <footer className="footer">
        <p>
          Abre <a href="http://localhost:15672">localhost:15672</a> (guest / guest) para ver las colas en tiempo real.
        </p>
      </footer>

      <Toasts toasts={toasts} onDismiss={dismissToast} />
    </>
  );
}

function StatCard({ label, value, tone = 'neutral' }) {
  return (
    <div className={`stat tone-${tone}`}>
      <span key={value} className="stat-value">
        {value}
      </span>
      <span className="stat-label">{label}</span>
    </div>
  );
}

function QueueRow({ name, info, detail, alert = false }) {
  return (
    <div className={`queue-row ${alert ? 'has-alert' : ''}`}>
      <dt>
        <code>{name}</code>
        <span className="queue-detail">{detail}</span>
      </dt>
      <dd>
        <span className="queue-count">{info ? info.messages : '-'}</span>
        <span className="queue-unit">{info ? `mensajes · ${info.consumers} consumidor(es)` : 'sin datos'}</span>
      </dd>
    </div>
  );
}
