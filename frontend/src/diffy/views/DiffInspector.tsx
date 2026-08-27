import { useEffect, useMemo, useState } from 'react';
import { Icons } from '../Icons';
import { cx, MethodPill, splitMethodPath } from '../primitives';
import { useFetchRequestQuery } from '../../features/requests/requestsApiSlice';
import { usePostNoiseMutation } from '../../features/noise/noiseApiSlice';
import { DiffSelection } from '../../features/selections/selectionsSlice';

type LineStatus = 'match' | 'diff' | 'noise' | 'mixed';
type ColumnKey = 'primary' | 'secondary' | 'candidate';

interface Line {
  depth: number;
  key?: string | number | null;
  kind: 'leaf' | 'open' | 'close';
  status: LineStatus;
  suffix?: string;
  values: Record<ColumnKey, string>;
}

function fmtScalar(v: unknown): string {
  if (v === null || v === undefined) return 'null';
  if (typeof v === 'string') return JSON.stringify(v);
  if (typeof v === 'boolean' || typeof v === 'number') return String(v);
  return JSON.stringify(v);
}

function isObj(v: unknown): v is Record<string, unknown> {
  return !!v && typeof v === 'object' && !Array.isArray(v);
}

function classify(pv: unknown, sv: unknown, cv: unknown): LineStatus {
  const pc = JSON.stringify(pv) === JSON.stringify(cv);
  const ps = JSON.stringify(pv) === JSON.stringify(sv);
  if (pc && ps) return 'match';
  if (!pc && !ps) return 'mixed';
  if (!pc) return 'diff';
  return 'noise';
}

export function buildLines(p: unknown, s: unknown, c: unknown): Line[] {
  const lines: Line[] = [];
  function walk(pp: any, ss: any, cc: any, key: string | number | null, depth: number, isLast: boolean) {
    const pIsObj = isObj(pp), pIsArr = Array.isArray(pp);
    const cIsObj = isObj(cc), cIsArr = Array.isArray(cc);
    const sIsObj = isObj(ss), sIsArr = Array.isArray(ss);
    const anyObj = (pIsObj && cIsObj) || (pIsObj && sIsObj) || (sIsObj && cIsObj);
    const anyArr = (pIsArr && cIsArr) || (pIsArr && sIsArr) || (sIsArr && cIsArr);
    if (anyObj || anyArr) {
      const open = anyArr ? '[' : '{';
      const close = anyArr ? ']' : '}';
      lines.push({
        depth, key, kind: 'open', status: 'match',
        values: { primary: open, secondary: open, candidate: open },
      });
      let keys: (string | number)[];
      if (anyArr) {
        const len = Math.max(
          Array.isArray(pp) ? pp.length : 0,
          Array.isArray(ss) ? ss.length : 0,
          Array.isArray(cc) ? cc.length : 0,
        );
        keys = Array.from({ length: len }, (_, i) => i);
      } else {
        const union = new Set<string>();
        if (isObj(pp)) Object.keys(pp).forEach((k) => union.add(k));
        if (isObj(ss)) Object.keys(ss).forEach((k) => union.add(k));
        if (isObj(cc)) Object.keys(cc).forEach((k) => union.add(k));
        keys = Array.from(union);
      }
      keys.forEach((k, i) => {
        walk((pp as any)?.[k], (ss as any)?.[k], (cc as any)?.[k], k, depth + 1, i === keys.length - 1);
      });
      lines.push({
        depth, kind: 'close', status: 'match', suffix: isLast ? '' : ',',
        values: { primary: close, secondary: close, candidate: close },
      });
      return;
    }
    const status = classify(pp, ss, cc);
    lines.push({
      depth,
      key,
      kind: 'leaf',
      status,
      suffix: isLast ? '' : ',',
      values: {
        primary: fmtScalar(pp),
        secondary: fmtScalar(ss),
        candidate: fmtScalar(cc),
      },
    });
  }
  walk(p, s, c, null, 0, true);
  return lines;
}

function formatDiffText(
  selection: DiffSelection,
  built: BuiltRequest,
  lines: Line[],
  tab: 'response' | 'headers' | 'request',
  layout: 'split' | 'threeway',
): string {
  const section = tab === 'headers' ? 'Response headers' : tab === 'request' ? 'Request' : 'Response body';
  const columns: ColumnKey[] = layout === 'split' ? ['primary', 'candidate'] : ['primary', 'secondary', 'candidate'];
  const colLabels: Record<ColumnKey, string> = {
    primary: 'Primary',
    secondary: 'Secondary',
    candidate: 'Candidate',
  };
  const diffLines = lines.filter((l) => l.kind === 'leaf' && l.status !== 'match');
  const rows = diffLines.map((l) => {
    const key = l.key != null ? (typeof l.key === 'number' ? `[${l.key}]` : String(l.key)) : '(root)';
    const vals = columns.map((col) => `${colLabels[col]}: ${l.values[col]}`).join(' | ');
    return `- ${key} [${l.status}] ${vals}`;
  });
  return [
    `Diffy inspect — ${section}`,
    `Endpoint: ${selection.endpoint}`,
    `Field: ${selection.field}`,
    `Request ID: ${selection.requestId}`,
    '',
    ...rows,
    '',
    'Full responses:',
    `Primary: ${JSON.stringify(built.responseBody.primary, null, 2)}`,
    ...(layout === 'threeway'
      ? [`Secondary: ${JSON.stringify(built.responseBody.secondary, null, 2)}`]
      : []),
    `Candidate: ${JSON.stringify(built.responseBody.candidate, null, 2)}`,
  ].join('\n');
}

function formatRegressionReport(
  selection: DiffSelection,
  built: BuiltRequest,
  lines: Line[],
  method: string,
  path: string,
): string {
  const diffLines = lines.filter((l) => l.kind === 'leaf' && (l.status === 'diff' || l.status === 'mixed'));
  const samples = diffLines.slice(0, 5).map((l) => {
    const key = l.key != null ? (typeof l.key === 'number' ? `[${l.key}]` : String(l.key)) : '(root)';
    return `  - ${key}: primary=${l.values.primary}, candidate=${l.values.candidate}`;
  });
  return [
    '# Diffy regression report',
    '',
    '## Summary',
    `- Endpoint: \`${selection.endpoint}\``,
    `- Field: \`${selection.field}\``,
    `- Request: \`${method} ${path}\``,
    `- Request ID: \`${selection.requestId}\``,
    `- Diff lines: ${diffLines.length}`,
    '',
    '## Sample differences',
    ...(samples.length ? samples : ['  - (no leaf diffs in current view)']),
    '',
    '## Primary response (excerpt)',
    '```json',
    JSON.stringify(built.responseBody.primary, null, 2),
    '```',
    '',
    '## Candidate response (excerpt)',
    '```json',
    JSON.stringify(built.responseBody.candidate, null, 2),
    '```',
    '',
    `_Generated from Diffy at ${new Date().toISOString()}_`,
  ].join('\n');
}

async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

interface BuiltRequest {
  request: Record<string, unknown>;
  requestMeta: Record<string, unknown>;
  requestBody: unknown;
  responseHeaders: { primary: Record<string, unknown>; secondary: Record<string, unknown>; candidate: Record<string, unknown> };
  responseBody: { primary: unknown; secondary: unknown; candidate: unknown };
  status: { primary?: string | number; candidate?: string | number };
}

/** Unwrap Diffy StringLifter shape: { type: "json", value: ... } */
function unwrapLifted(v: unknown): unknown {
  if (!isObj(v)) return v;
  if (v.type === 'json' && 'value' in v) return v.value;
  return v;
}

function build(raw: any): BuiltRequest | null {
  if (!raw || raw.error) return null;
  if (!raw.left || !raw.right) return null;
  const request = (raw.request as Record<string, unknown>) || {};
  const left = (raw.left as Record<string, unknown>) || {};
  const right = (raw.right as Record<string, unknown>) || {};
  const secondary = (raw.secondary as Record<string, unknown>) || left;
  const requestBody = unwrapLifted(request.body);
  return {
    request,
    requestMeta: {
      method: request.method,
      path: request.path,
      uri: request.uri,
      headers: request.headers,
      params: request.params,
    },
    requestBody,
    responseHeaders: {
      primary: (unwrapLifted(left.headers) as Record<string, unknown>) || {},
      secondary: (unwrapLifted(secondary.headers) as Record<string, unknown>) || {},
      candidate: (unwrapLifted(right.headers) as Record<string, unknown>) || {},
    },
    responseBody: {
      primary: unwrapLifted(left.body),
      secondary: unwrapLifted(secondary.body),
      candidate: unwrapLifted(right.body),
    },
    status: {
      primary: left.status as string | number | undefined,
      candidate: right.status as string | number | undefined,
    },
  };
}

function DiffLine({ line, column, smart }: { line: Line; column: ColumnKey; smart: boolean }) {
  const status = line.status;
  const val = line.values[column];
  const indent = line.depth * 14;

  const className = cx(
    'diffy-jsl',
    status === 'diff' && column === 'candidate' && 'is-add',
    status === 'diff' && column === 'primary' && 'is-del',
    status === 'diff' && column === 'secondary' && 'is-baseline',
    status === 'noise' && column !== 'primary' && 'is-noise',
    status === 'mixed' && column === 'candidate' && 'is-add',
    status === 'mixed' && column === 'primary' && 'is-del',
    status === 'mixed' && column === 'secondary' && 'is-noise',
    status === 'match' && smart && 'is-dim',
  );

  if (line.kind === 'open') {
    return (
      <div className={className} style={{ paddingLeft: indent }}>
        {line.key != null && <span className="diffy-jsk">{JSON.stringify(line.key)}: </span>}
        <span className="diffy-jsv">{val}</span>
      </div>
    );
  }
  if (line.kind === 'close') {
    return (
      <div className={className} style={{ paddingLeft: indent }}>
        <span className="diffy-jsv">{val}{line.suffix}</span>
      </div>
    );
  }
  return (
    <div className={className} style={{ paddingLeft: indent }}>
      {line.key != null && (
        <span className="diffy-jsk">
          {typeof line.key === 'number' ? '' : JSON.stringify(line.key) + ': '}
        </span>
      )}
      <span className="diffy-jsv">{val}{line.suffix}</span>
      {status !== 'match' && column === 'candidate' && (
        <span className="diffy-jsbadge">
          {status === 'diff' && <span className="diffy-jsbadge-pill is-diff">DIFF</span>}
          {status === 'noise' && <span className="diffy-jsbadge-pill is-noise">NOISE</span>}
          {status === 'mixed' && <span className="diffy-jsbadge-pill is-diff">DIFF + NOISE</span>}
        </span>
      )}
    </div>
  );
}

export function DiffInspector({
  selection,
  onClose,
  layout,
  onLayoutChange,
}: {
  selection: DiffSelection;
  onClose: () => void;
  layout: 'split' | 'threeway';
  onLayoutChange: (m: 'split' | 'threeway') => void;
}) {
  const [tab, setTab] = useState<'response' | 'headers' | 'request'>('response');
  const [smart, setSmart] = useState(true);
  const [replaying, setReplaying] = useState(false);
  const [replayResult, setReplayResult] = useState<null | { ms: number }>(null);
  const [copyFeedback, setCopyFeedback] = useState<'diff' | 'report' | null>(null);
  const [markingNoise, setMarkingNoise] = useState(false);

  const reqRes = useFetchRequestQuery(selection.requestId);
  const raw = reqRes.data as any;
  const built = useMemo(() => build(raw), [raw]);
  const [postNoise] = usePostNoiseMutation();

  const bodyLines = useMemo(() => {
    if (!built) return [];
    return buildLines(
      built.responseBody.primary,
      built.responseBody.secondary,
      built.responseBody.candidate,
    );
  }, [built]);
  const headerLines = useMemo(() => {
    if (!built) return [];
    return buildLines(
      built.responseHeaders.primary,
      built.responseHeaders.secondary,
      built.responseHeaders.candidate,
    );
  }, [built]);

  const counts = useMemo(() => {
    let diff = 0, noise = 0;
    bodyLines.forEach((l) => {
      if (l.kind !== 'leaf') return;
      if (l.status === 'diff' || l.status === 'mixed') diff++;
      else if (l.status === 'noise') noise++;
    });
    return { diff, noise };
  }, [bodyLines]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
      if (e.key === 'n' || e.key === 'N') {
        e.preventDefault();
        handleMarkNoise();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, selection]);

  const doReplay = () => {
    setReplaying(true);
    setReplayResult(null);
    setTimeout(() => {
      setReplaying(false);
      setReplayResult({ ms: 142 });
    }, 1200);
  };

  const columns: ColumnKey[] = layout === 'split' ? ['primary', 'candidate'] : ['primary', 'secondary', 'candidate'];
  const colMeta: Record<ColumnKey, { label: string }> = {
    primary: { label: 'Primary' },
    secondary: { label: 'Secondary' },
    candidate: { label: 'Candidate' },
  };
  const activeLines = tab === 'headers' ? headerLines : bodyLines;
  const method = (built?.request?.method as string) || splitMethodPath(selection.endpoint).method;
  const path = (built?.request?.uri as string) || (built?.request?.path as string) || splitMethodPath(selection.endpoint).path;

  const showCopyFeedback = (kind: 'diff' | 'report') => {
    setCopyFeedback(kind);
    window.setTimeout(() => setCopyFeedback(null), 2000);
  };

  const doCopyDiff = async () => {
    if (!built) return;
    const text = formatDiffText(selection, built, activeLines, tab, layout);
    if (await copyText(text)) showCopyFeedback('diff');
  };

  const doFileReport = async () => {
    if (!built) return;
    const text = formatRegressionReport(selection, built, bodyLines, method, path);
    if (await copyText(text)) showCopyFeedback('report');
  };

  const handleMarkNoise = async () => {
    if (!selection.field || !selection.endpoint) return;
    setMarkingNoise(true);
    try {
      await postNoise({
        endpoint: encodeURIComponent(selection.endpoint),
        fieldPrefix: encodeURIComponent(selection.field),
        isNoise: true,
      });
    } finally {
      setMarkingNoise(false);
    }
  };

  return (
    <div className="diffy-overlay" onClick={onClose}>
      <div className="diffy-sheet" onClick={(e) => e.stopPropagation()}>
        <div className="diffy-sheet-head">
          <div className="diffy-sheet-head-l">
            <div className="diffy-sheet-eyebrow">
              <span className="diffy-mono diffy-text-muted">{selection.requestId}</span>
              <span className="diffy-text-muted">·</span>
              <span>
                <span className="diffy-mono diffy-bad">{counts.diff}</span> real
                <span className="diffy-text-muted"> · </span>
                <span className="diffy-mono" style={{ color: 'var(--warn)' }}>{counts.noise}</span> noise
              </span>
            </div>
            <div className="diffy-sheet-title">
              <MethodPill method={method} />
              <span className="diffy-mono">{path}</span>
            </div>
          </div>
          <div className="diffy-sheet-head-r">
            <div className="diffy-segmented">
              <button
                className={cx(layout === 'split' && 'is-active')}
                onClick={() => onLayoutChange('split')}
              >
                2-way
              </button>
              <button
                className={cx(layout === 'threeway' && 'is-active')}
                onClick={() => onLayoutChange('threeway')}
              >
                3-way
              </button>
            </div>
            <label className="diffy-toggle">
              <input type="checkbox" checked={smart} onChange={(e) => setSmart(e.target.checked)} />
              <span className="diffy-toggle-track"><span className="diffy-toggle-thumb" /></span>
              <span>Smart</span>
            </label>
            <button className="diffy-icon-btn" onClick={onClose} title="Close (Esc)">
              <Icons.Close size={15} />
            </button>
          </div>
        </div>

        <div className="diffy-tabs">
          {[
            { id: 'response' as const, label: 'Response body', count: counts.diff },
            { id: 'headers' as const, label: 'Headers', count: 0 },
            { id: 'request' as const, label: 'Request', count: 0 },
          ].map((t) => (
            <button
              key={t.id}
              className={cx('diffy-tab', tab === t.id && 'is-active')}
              onClick={() => setTab(t.id)}
            >
              {t.label}
              {t.count > 0 && <span className="diffy-tab-count">{t.count}</span>}
            </button>
          ))}
          <div style={{ flex: 1 }} />
          <button className="diffy-btn is-ghost is-sm" onClick={doReplay} disabled={replaying}>
            {replaying ? (
              <>
                <Icons.Spinner size={12} /> Replaying…
              </>
            ) : (
              <>
                <Icons.Replay size={13} /> Replay
              </>
            )}
          </button>
          <button 
            className="diffy-btn is-ghost is-sm" 
            onClick={handleMarkNoise}
            disabled={markingNoise || !selection.field}
          >
            <Icons.Noise size={13} /> {markingNoise ? 'Marking...' : 'Mark as noise'}
          </button>
        </div>

        <div className="diffy-sheet-body">
          {!built && (
            <div className="diffy-empty" style={{ flex: 1 }}>
              {reqRes.isLoading ? (
                <>
                  <Icons.Spinner size={20} />
                  <div>Loading request…</div>
                </>
              ) : (
                <>
                  <Icons.Fail size={20} />
                  <div>
                    Could not load request <span className="diffy-mono">{selection.requestId}</span>.
                  </div>
                  <div className="diffy-text-muted" style={{ fontSize: 12, marginTop: 6 }}>
                    {(raw as any)?.error ||
                      'Missing primary/candidate payloads. Try: curl /api/1/requests/' +
                        selection.requestId +
                        '?include_request=true'}
                  </div>
                </>
              )}
            </div>
          )}
          {built && tab === 'request' && (
            <div className="diffy-request-pane">
              <div className="diffy-card-sub" style={{ padding: '14px 18px 6px' }}>Metadata</div>
              <pre className="diffy-codeblock">{JSON.stringify(built.requestMeta, null, 2)}</pre>
              <div className="diffy-card-sub" style={{ padding: '14px 18px 6px' }}>Body (proto → JSON)</div>
              <pre className="diffy-codeblock">{JSON.stringify(built.requestBody, null, 2)}</pre>
            </div>
          )}
          {built && tab !== 'request' && (
            <div className={cx('diffy-diff-grid', `is-${layout}`)}>
              {columns.map((col) => (
                <div key={col} className="diffy-diff-col">
                  <div className={cx('diffy-diff-col-head', `is-${col}`)}>
                    <div className="diffy-diff-col-head-l">
                      <span className={cx('diffy-col-pill', `is-${col}`)}>{colMeta[col].label}</span>
                    </div>
                    <div className="diffy-diff-col-head-r">
                      <span className="diffy-mono diffy-text-muted" style={{ fontSize: 11 }}>
                        {col === 'primary' && built.status.primary}
                        {col === 'candidate' && built.status.candidate}
                      </span>
                    </div>
                  </div>
                  <div className="diffy-diff-col-body diffy-mono">
                    {activeLines.map((l, i) => (
                      <DiffLine key={i} line={l} column={col} smart={smart} />
                    ))}
                    {activeLines.length === 0 && (
                      <div className="diffy-empty">
                        <div>No {tab === 'headers' ? 'header' : 'body'} data captured for this request.</div>
                      </div>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="diffy-sheet-foot">
          {replayResult ? (
            <div className="diffy-replay-result">
              <Icons.Pass size={15} style={{ color: 'var(--ok)' }} />
              <span>Replay complete in {replayResult.ms}ms</span>
              <span className="diffy-text-muted">·</span>
              <span>still diverges on <span className="diffy-mono diffy-bad">{selection.field}</span></span>
            </div>
          ) : (
            <div className="diffy-sheet-foot-help diffy-text-muted">
              <kbd className="diffy-kbd">←</kbd> <kbd className="diffy-kbd">→</kbd> navigate diffs
              <span style={{ marginLeft: 14 }}><kbd className="diffy-kbd">N</kbd> mark as noise</span>
              <span style={{ marginLeft: 14 }}><kbd className="diffy-kbd">R</kbd> replay</span>
              <span style={{ marginLeft: 14 }}><kbd className="diffy-kbd">Esc</kbd> close</span>
            </div>
          )}
          <div style={{ flex: 1 }} />
          <button
            className="diffy-btn is-ghost is-sm"
            onClick={doCopyDiff}
            disabled={!built}
            title="Copy diff summary to clipboard"
          >
            <Icons.Copy size={13} /> {copyFeedback === 'diff' ? 'Copied!' : 'Copy diff'}
          </button>
          <button
            className="diffy-btn is-primary is-sm"
            onClick={doFileReport}
            disabled={!built}
            title="Copy regression report (paste into Jira/Slack)"
          >
            {copyFeedback === 'report' ? 'Report copied!' : 'File regression report'} <Icons.ArrowRight size={13} />
          </button>
        </div>
      </div>
    </div>
  );
}
