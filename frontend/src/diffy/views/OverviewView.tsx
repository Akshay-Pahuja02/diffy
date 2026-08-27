import { Icons } from '../Icons';
import { cx, fmtN, MethodPill, Sparkbars, splitMethodPath, Stat, verdictLabel } from '../primitives';
import { Run } from '../../features/runs/runsApiSlice';
import { useDeleteRequestsMutation, apiInfoSlice } from '../../features/info/infoApiSlice';
import { apiNoiseSlice } from '../../features/noise/noiseApiSlice';
import { apiRunsSlice } from '../../features/runs/runsApiSlice';
import { useAppDispatch } from '../../app/hooks';
import { useTimeseries } from './useTimeseries';

export interface EndpointSummary {
  path: string;
  requests: number;
  diffs: number;
  diffRate: number;
}

export function OverviewView({
  run,
  endpoints,
  onOpenEndpoint,
  onOpenView,
}: {
  run: Run;
  endpoints: EndpointSummary[];
  onOpenEndpoint: (path: string) => void;
  onOpenView: (v: 'endpoints') => void;
}) {
  const dispatch = useAppDispatch();
  const [clearRequests, clearState] = useDeleteRequestsMutation();
  const reset = async () => {
    await clearRequests();
    dispatch(apiNoiseSlice.util.invalidateTags(['Noise']));
    dispatch(apiInfoSlice.util.resetApiState());
    dispatch(apiRunsSlice.util.invalidateTags(['Runs']));
  };
  const verdict: 'running' | 'pass' | 'fail' =
    run.status === 'running' ? 'running' : (run.verdict || 'pass');

  const verdictText: Record<typeof verdict, string> = {
    running: 'Run in progress',
    pass: 'Safe to ship',
    fail: 'Regressions detected',
  };

  const ratio = run.realDiffs / Math.max(1, run.noiseDiffs);
  const confidence = Math.max(0, Math.min(1, 1 - Math.min(1, ratio * 8)));

  const topFailing = [...endpoints]
    .filter((e) => e.diffs > 0)
    .sort((a, b) => b.diffs - a.diffs)
    .slice(0, 5);

  const windowMinutes = 40;
  const ts = useTimeseries(40, windowMinutes);
  const reqArr = ts.buckets.map((b) => b.requests);
  const diffArr = ts.buckets.map((b) => b.diffs);
  const totalReqInWindow = reqArr.reduce((a, b) => a + b, 0);
  const totalDiffInWindow = diffArr.reduce((a, b) => a + b, 0);
  const anyData = totalReqInWindow + totalDiffInWindow > 0;

  return (
    <div className="diffy-page">
      <section className={cx('diffy-verdict', `is-${verdict}`)}>
        <div className="diffy-verdict-left">
          <div className="diffy-verdict-icon">
            {verdict === 'running' && <Icons.Spinner size={28} />}
            {verdict === 'pass' && <Icons.Pass size={28} stroke={2.5} />}
            {verdict === 'fail' && <Icons.Fail size={28} />}
          </div>
          <div>
            <div className="diffy-eyebrow">{verdictLabel(verdict)} verdict</div>
            <h1 className="diffy-verdict-title">{verdictText[verdict]}</h1>
            <div className="diffy-verdict-sub">
              {verdict === 'running' ? (
                <>
                  Comparing <b>candidate</b> against <b>primary</b> · {run.totalReqs.toLocaleString()} requests processed
                </>
              ) : verdict === 'pass' ? (
                <>
                  Candidate matches primary within noise tolerance · {fmtN(run.totalReqs)} requests sampled
                </>
              ) : (
                <>
                  <b>{run.realDiffs.toLocaleString()}</b> real regressions across <b>{topFailing.length}</b> endpoints
                </>
              )}
            </div>
          </div>
        </div>
        <div className="diffy-verdict-right">
          <div className="diffy-confidence">
            <div className="diffy-confidence-label">Signal-to-noise</div>
            <div className="diffy-confidence-bar">
              <div className="diffy-confidence-fill" style={{ width: `${Math.round(confidence * 100)}%` }} />
            </div>
            <div className="diffy-confidence-numbers">
              <span><b>{run.realDiffs.toLocaleString()}</b> real</span>
              <span className="diffy-text-muted">·</span>
              <span><b>{run.noiseDiffs.toLocaleString()}</b> noise</span>
            </div>
          </div>
          <div className="diffy-verdict-actions">
            {verdict === 'running' && (
              <button className="diffy-btn is-ghost" onClick={reset} disabled={clearState.isLoading}>
                <Icons.Pause size={13} /> {clearState.isLoading ? 'Resetting…' : 'Reset run'}
              </button>
            )}
            <button className="diffy-btn is-primary" onClick={() => onOpenView('endpoints')}>
              <Icons.Sparkle size={13} /> Review diffs
            </button>
          </div>
        </div>
      </section>

      <section className="diffy-card-row">
        <div className="diffy-card diffy-stat-card">
          <Stat label="Total requests" value={run.totalReqs.toLocaleString()} sub={`${run.duration} elapsed`} />
        </div>
        <div className="diffy-card diffy-stat-card">
          <Stat 
            label="Requests with diffs" 
            value={run.realDiffs.toLocaleString()} 
            sub={`${((run.realDiffs / Math.max(1, run.totalReqs)) * 100).toFixed(2)}% of total`}
            accent={run.realDiffs > 0 ? 'var(--bad)' : 'var(--ok)'}
          />
        </div>
        <div className="diffy-card diffy-stat-card">
          <Stat
            label="Real diff rate"
            value={`${run.diffRate.toFixed(2)}%`}
            sub="threshold 0.50%"
            accent={run.diffRate > 0.5 ? 'var(--bad)' : 'var(--ok)'}
          />
        </div>
        <div className="diffy-card diffy-stat-card">
          <Stat label="Noise rate" value={`${run.noiseRate.toFixed(1)}%`} sub="from primary↔secondary" />
        </div>
        <div className="diffy-card diffy-stat-card">
          <Stat
            label="Endpoints touched"
            value={endpoints.length}
            sub={`${endpoints.filter((e) => e.diffs > 0).length} with diffs`}
          />
        </div>
      </section>

      <section className="diffy-grid-2">
        <div className="diffy-card">
          <div className="diffy-card-head">
            <div>
              <div className="diffy-card-title">Traffic & diffs</div>
              <div className="diffy-card-sub">
                Last {windowMinutes} minutes · {totalReqInWindow.toLocaleString()} requests · {totalDiffInWindow.toLocaleString()} diffs
              </div>
            </div>
            <div className="diffy-chart-legend">
              <span><span className="diffy-legend-dot" style={{ background: 'var(--text-3)' }} />Requests</span>
              <span><span className="diffy-legend-dot" style={{ background: 'var(--bad)' }} />Diffs</span>
            </div>
          </div>
          {ts.loading ? (
            <div className="diffy-empty"><Icons.Spinner size={18} /><div>Sampling time window…</div></div>
          ) : !anyData ? (
            <div className="diffy-empty"><div>No traffic captured in the last {windowMinutes} minutes.</div></div>
          ) : (
            <>
              <div className="diffy-chart-stack">
                <div className="diffy-chart-row">
                  <Sparkbars values={reqArr} height={56} color="var(--text-3)" />
                </div>
                <div className="diffy-chart-row">
                  <Sparkbars values={diffArr} height={56} color="var(--bad)" />
                </div>
              </div>
              <div className="diffy-chart-axis">
                <span>{windowMinutes}m ago</span>
                <span>{Math.round(windowMinutes * 0.75)}m</span>
                <span>{Math.round(windowMinutes * 0.5)}m</span>
                <span>{Math.round(windowMinutes * 0.25)}m</span>
                <span>now</span>
              </div>
            </>
          )}
        </div>

        <div className="diffy-card">
          <div className="diffy-card-head">
            <div className="diffy-card-title">Top failing endpoints</div>
            <button className="diffy-link" onClick={() => onOpenView('endpoints')}>
              All endpoints <Icons.Chevron size={11} />
            </button>
          </div>
          <div className="diffy-toplist">
            {topFailing.map((e) => {
              const { method, path } = splitMethodPath(e.path);
              return (
                <button
                  key={e.path}
                  className="diffy-toplist-row"
                  onClick={() => onOpenEndpoint(e.path)}
                >
                  <div className="diffy-toplist-main">
                    <MethodPill method={method} />
                    <span className="diffy-toplist-path">{path}</span>
                  </div>
                  <div className="diffy-toplist-meta">
                    <span className="diffy-text-muted diffy-mono" style={{ fontSize: 11 }}>
                      {e.diffs} diffs
                    </span>
                    <div className="diffy-bar-track">
                      <div
                        className="diffy-bar-fill is-bad"
                        style={{ width: `${Math.min(100, e.diffRate * 60)}%` }}
                      />
                    </div>
                    <span className="diffy-mono" style={{ width: 44, textAlign: 'right', fontSize: 11 }}>
                      {e.diffRate.toFixed(2)}%
                    </span>
                  </div>
                </button>
              );
            })}
            {topFailing.length === 0 && (
              <div className="diffy-empty">
                <Icons.Pass size={20} />
                <div>No failing endpoints in this run.</div>
              </div>
            )}
          </div>
        </div>
      </section>

      <section className="diffy-card">
        <div className="diffy-card-head">
          <div className="diffy-card-title">Run configuration</div>
        </div>
        <div className="diffy-kv-grid">
          <div className="diffy-kv"><div className="diffy-kv-k">Candidate</div><div className="diffy-kv-v diffy-mono">{run.candidate}</div></div>
          <div className="diffy-kv"><div className="diffy-kv-k">Primary</div><div className="diffy-kv-v diffy-mono">{run.primary}</div></div>
          <div className="diffy-kv"><div className="diffy-kv-k">Secondary</div><div className="diffy-kv-v diffy-mono">{run.secondary}</div></div>
          <div className="diffy-kv"><div className="diffy-kv-k">Author</div><div className="diffy-kv-v">{run.author} <span className="diffy-text-muted">·</span> <span className="diffy-mono">{run.branch}</span></div></div>
          <div className="diffy-kv"><div className="diffy-kv-k">Started</div><div className="diffy-kv-v">{run.startedAt}</div></div>
          <div className="diffy-kv"><div className="diffy-kv-k">Thresholds</div><div className="diffy-kv-v diffy-mono">abs 0.03 · rel 20.0%</div></div>
        </div>
      </section>
    </div>
  );
}
