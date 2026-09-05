import { useMemo, useState } from 'react';
import { Icons } from '../Icons';
import { cx, Tag } from '../primitives';
import {
  useFetchEndpointsQuery,
  useFetchNoiseQuery,
  usePostNoiseMutation,
} from '../../features/noise/noiseApiSlice';

interface Suggestion {
  reason: string;
  field: string;
  endpoint: string;
  confidence: number;
  warn: boolean;
  hint?: string;
  affectedRequests: number;
  sampleLeft: string;
  sampleRight: string;
}

const NOISE_HINTS: { pattern: RegExp; reason: string; conf: number }[] = [
  { pattern: /date|timestamp|last_seen|created_at|updated_at/i, reason: 'Looks like a timestamp', conf: 0.95 },
  { pattern: /request-?id|trace-?id|correlation/i, reason: 'Looks like a request id', conf: 0.97 },
  { pattern: /etag|x-amz|x-request|x-trace/i, reason: 'Looks like a request-scoped header', conf: 0.9 },
];

export function NoiseView({
  endpoint,
  dateRange,
}: {
  endpoint?: string;
  dateRange: { start: number; end: number };
}) {
  const [showAddRule, setShowAddRule] = useState(false);
  const [newRulePrefix, setNewRulePrefix] = useState('');
  
  const epRes = useFetchEndpointsQuery({
    excludeNoise: false,
    start: dateRange.start,
    end: dateRange.end,
  });
  const endpoints = (epRes.data as Record<string, { total: number; differences: number }>) || {};
  const allEndpoints = Object.keys(endpoints);

  const target = endpoint || allEndpoints[0];
  const noiseRes = useFetchNoiseQuery(target ? encodeURIComponent(target) : '', { skip: !target });
  const rules = (noiseRes.data as string[]) || [];

  const [postNoise] = usePostNoiseMutation();

  // Auto-suggestions: pattern match on top-level endpoints' field-paths via heuristic
  const suggestions: Suggestion[] = useMemo(() => {
    if (!target) return [];
    const out: Suggestion[] = [];
    for (const { pattern, reason, conf } of NOISE_HINTS) {
      // We don't have direct access to field paths here without /stats — surface heuristic over endpoint name
      if (pattern.test(target)) {
        out.push({
          reason,
          field: target,
          endpoint: target,
          confidence: conf,
          warn: false,
          affectedRequests: endpoints[target]?.differences || 0,
          sampleLeft: '—',
          sampleRight: '—',
        });
      }
    }
    return out;
  }, [target, endpoints]);

  const accept = async (s: Suggestion) => {
    await postNoise({
      endpoint: encodeURIComponent(s.endpoint),
      fieldPrefix: encodeURIComponent(s.field),
      isNoise: true,
    });
  };

  const handleAddRule = async () => {
    if (!target || !newRulePrefix.trim()) return;
    await postNoise({
      endpoint: encodeURIComponent(target),
      fieldPrefix: encodeURIComponent(newRulePrefix.trim()),
      isNoise: true,
    });
    setNewRulePrefix('');
    setShowAddRule(false);
  };

  return (
    <div className="diffy-page">
      <section className="diffy-card">
        <div className="diffy-card-head">
          <div>
            <div className="diffy-card-title">
              <Icons.Sparkle size={14} style={{ color: 'var(--accent)', verticalAlign: -2, marginRight: 6 }} />
              Suggested noise rules
            </div>
            <div className="diffy-card-sub">
              {suggestions.length} fields look non-deterministic
              {target ? ` in ${target}` : ''}
            </div>
          </div>
          <button className="diffy-link">Re-scan</button>
        </div>
        <div className="diffy-suggest-grid">
          {suggestions.map((s, i) => (
            <div key={i} className={cx('diffy-suggest', s.warn && 'is-warn')}>
              <div className="diffy-suggest-head">
                <span className="diffy-suggest-reason">{s.reason}</span>
                <div className="diffy-suggest-conf">
                  <span className="diffy-mono">{Math.round(s.confidence * 100)}%</span>
                  <div className="diffy-conf-bar">
                    <div
                      className="diffy-conf-fill"
                      style={{
                        width: `${s.confidence * 100}%`,
                        background: s.warn ? 'var(--warn)' : 'var(--ok)',
                      }}
                    />
                  </div>
                </div>
              </div>
              <div className="diffy-suggest-field diffy-mono">{s.field}</div>
              <div className="diffy-suggest-sample">
                <div className="diffy-suggest-sample-row">
                  <span className="diffy-col-pill is-primary">P</span>
                  <code className="diffy-mono">{s.sampleLeft}</code>
                </div>
                <div className="diffy-suggest-sample-row">
                  <span className="diffy-col-pill is-candidate">C</span>
                  <code className="diffy-mono">{s.sampleRight}</code>
                </div>
              </div>
              {s.hint && (
                <div className="diffy-suggest-hint">
                  <Icons.Sparkle size={11} /> {s.hint}
                </div>
              )}
              <div className="diffy-suggest-foot">
                <span className="diffy-text-muted" style={{ fontSize: 11 }}>
                  affects <b className={s.warn ? 'diffy-bad' : ''}>{s.affectedRequests}</b> requests
                </span>
                <div style={{ flex: 1 }} />
                <button className="diffy-btn is-ghost is-sm">Dismiss</button>
                <button
                  className={cx('diffy-btn is-sm', s.warn ? 'is-ghost-warn' : 'is-primary')}
                  onClick={() => accept(s)}
                >
                  {s.warn ? 'Review & ignore' : 'Ignore field'}
                </button>
              </div>
            </div>
          ))}
          {suggestions.length === 0 && (
            <div className="diffy-empty diffy-empty-full">
              <Icons.Pass size={22} />
              <div>Nothing else looks like noise.</div>
            </div>
          )}
        </div>
      </section>

      <section className="diffy-card">
        <div className="diffy-card-head">
          <div>
            <div className="diffy-card-title">Active noise rules</div>
            <div className="diffy-card-sub">
              {rules.length} rules silencing known non-determinism
              {target ? ` for ${target}` : ''}
            </div>
          </div>
          <button className="diffy-btn is-ghost is-sm" onClick={() => setShowAddRule(!showAddRule)}>
            <Icons.Plus size={12} /> Add rule
          </button>
        </div>
        {showAddRule && (
          <div style={{ padding: '0 18px 12px', display: 'flex', gap: 8 }}>
            <input
              type="text"
              className="diffy-input"
              placeholder="e.g. response.sla or response.deliveryPromise"
              value={newRulePrefix}
              onChange={(e) => setNewRulePrefix(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleAddRule();
                if (e.key === 'Escape') setShowAddRule(false);
              }}
              style={{ flex: 1, padding: '6px 10px', fontSize: 13 }}
              autoFocus
            />
            <button className="diffy-btn is-primary is-sm" onClick={handleAddRule} disabled={!newRulePrefix.trim()}>
              Add
            </button>
            <button className="diffy-btn is-ghost is-sm" onClick={() => setShowAddRule(false)}>
              Cancel
            </button>
          </div>
        )}
        <div className="diffy-table">
          <div className="diffy-table-head">
            <div style={{ flex: 1.4 }}>Field prefix</div>
            <div style={{ flex: 1 }}>Endpoint</div>
            <div style={{ width: 100 }}>Added by</div>
            <div style={{ width: 100, textAlign: 'right' }}>—</div>
            <div style={{ width: 70, textAlign: 'right' }}></div>
          </div>
          {rules.map((prefix) => (
            <div key={prefix} className="diffy-table-row is-static">
              <div style={{ flex: 1.4, minWidth: 0 }} className="diffy-mono" title={prefix}>
                {prefix}
              </div>
              <div style={{ flex: 1, minWidth: 0 }} className="diffy-mono diffy-text-muted">
                {target}
              </div>
              <div style={{ width: 100 }}>
                <Tag tone="accent" size="xs">user</Tag>
              </div>
              <div style={{ width: 100, textAlign: 'right' }} className="diffy-text-muted">
                —
              </div>
              <div style={{ width: 70, textAlign: 'right' }}>
                <button
                  className="diffy-icon-btn-sm"
                  title="Remove"
                  onClick={() =>
                    postNoise({
                      endpoint: encodeURIComponent(target || ''),
                      fieldPrefix: encodeURIComponent(prefix),
                      isNoise: false,
                    })
                  }
                >
                  <Icons.Close size={12} />
                </button>
              </div>
            </div>
          ))}
          {rules.length === 0 && (
            <div className="diffy-empty">
              <div>No active noise rules{target ? ` for ${target}` : ''}.</div>
            </div>
          )}
        </div>
      </section>
    </div>
  );
}
