import { useMemo, useState } from 'react';
import { Icons } from '../Icons';
import { cx, fmtN, MethodPill, splitMethodPath, Tag } from '../primitives';
import { buildFieldTree, flatten, maxFieldCount, stripTypeMarker } from './fieldTree';
import { EndpointSummary } from './OverviewView';
import { useFetchDifferencesQuery, useFetchFieldsQuery } from '../../features/noise/noiseApiSlice';
import { useFetchInfoQuery, useSetListComparisonModeMutation } from '../../features/info/infoApiSlice';
import { DiffSelection } from '../../features/selections/selectionsSlice';

interface Props {
  endpoints: EndpointSummary[];
  selected?: string;
  onSelectEndpoint: (path: string) => void;
  activeField?: string;
  onSelectField: (path: string) => void;
  onOpenDiff: (d: DiffSelection) => void;
  excludeNoise: boolean;
  onToggleNoise: () => void;
  dateRange: { start: number; end: number };
}

export function EndpointsWorkspace({
  endpoints,
  selected,
  onSelectEndpoint,
  activeField,
  onSelectField,
  onOpenDiff,
  excludeNoise,
  onToggleNoise,
  dateRange,
}: Props) {
  const failingCount = endpoints.filter((e) => e.diffs > 0).length;
  const infoRes = useFetchInfoQuery();
  const [setListMode] = useSetListComparisonModeMutation();
  const listMode = infoRes.data?.listComparisonMode || 'LEGACY';
  const indexedOn = listMode === 'INDEXED';

  return (
    <div className="diffy-page diffy-workspace">
      <div className="diffy-pane diffy-pane-endpoints">
        <div className="diffy-pane-head">
          <div className="diffy-pane-title">Endpoints</div>
          <div className="diffy-pane-sub">
            {endpoints.length} total · {failingCount} failing
          </div>
        </div>
        <div className="diffy-pane-tools">
          <label className="diffy-toggle">
            <input type="checkbox" checked={excludeNoise} onChange={onToggleNoise} />
            <span className="diffy-toggle-track">
              <span className="diffy-toggle-thumb" />
            </span>
            <span>Exclude noise</span>
          </label>
          <label className="diffy-toggle" title="Strict index-by-index list comparison (faster on large payloads)">
            <input
              type="checkbox"
              checked={indexedOn}
              onChange={() => setListMode(indexedOn ? 'LEGACY' : 'INDEXED')}
            />
            <span className="diffy-toggle-track">
              <span className="diffy-toggle-thumb" />
            </span>
            <span>Indexed lists</span>
          </label>
        </div>
        <div className="diffy-pane-list">
          {endpoints.map((ep) => {
            const active = ep.path === selected;
            const { method, path } = splitMethodPath(ep.path);
            return (
              <button
                key={ep.path}
                className={cx('diffy-endpoint-row', active && 'is-active', ep.diffs === 0 && 'is-clean')}
                onClick={() => onSelectEndpoint(ep.path)}
              >
                <div className="diffy-endpoint-top">
                  <MethodPill method={method} />
                  <span className="diffy-endpoint-path">{path}</span>
                </div>
                <div className="diffy-endpoint-bot">
                  <span className={cx('diffy-mono', ep.diffs > 0 && 'diffy-bad')}>
                    {ep.diffs > 0 ? `${fmtN(ep.diffs)} / ${fmtN(ep.requests)}` : 'clean'}
                  </span>
                  <span className="diffy-text-muted diffy-mono" style={{ fontSize: 10 }}>
                    {ep.diffs > 0 ? `${ep.diffRate.toFixed(1)}%` : `${fmtN(ep.requests)} req`}
                  </span>
                  <div className="diffy-bar-track diffy-bar-track-sm">
                    <div
                      className="diffy-bar-fill is-bad"
                      style={{ width: `${Math.min(100, ep.diffRate * 60)}%` }}
                    />
                  </div>
                </div>
              </button>
            );
          })}
          {endpoints.length === 0 && (
            <div className="diffy-empty">
              <div>No endpoints yet. Send traffic to the proxy.</div>
            </div>
          )}
        </div>
      </div>

      <FieldsPane
        endpoint={selected}
        excludeNoise={excludeNoise}
        dateRange={dateRange}
        activeField={activeField}
        onSelectField={onSelectField}
      />

      <DiffsPane
        endpoint={selected}
        activeField={activeField}
        excludeNoise={excludeNoise}
        dateRange={dateRange}
        onOpenDiff={onOpenDiff}
      />
    </div>
  );
}

function FieldsPane({
  endpoint,
  excludeNoise,
  dateRange,
  activeField,
  onSelectField,
}: {
  endpoint?: string;
  excludeNoise: boolean;
  dateRange: { start: number; end: number };
  activeField?: string;
  onSelectField: (path: string) => void;
}) {
  const [openSet, setOpenSet] = useState<Set<string>>(new Set());
  const toggleOpen = (p: string) =>
    setOpenSet((s) => {
      const n = new Set(s);
      if (n.has(p)) n.delete(p);
      else n.add(p);
      return n;
    });

  const args = endpoint
    ? {
        selectedEndpoint: encodeURIComponent(endpoint),
        includeWeights: false,
        excludeNoise,
        start: dateRange.start,
        end: dateRange.end,
      }
    : undefined;
  const fieldsRes = useFetchFieldsQuery(args!, { skip: !endpoint });
  const rawFields = (fieldsRes.data as any)?.fields as Record<string, any> | undefined;

  const tree = useMemo(() => buildFieldTree(rawFields), [rawFields]);

  const flat = useMemo(() => flatten(tree, openSet), [tree, openSet]);
  const maxCount = useMemo(() => maxFieldCount(tree), [tree]);

  return (
    <div className="diffy-pane diffy-pane-fields">
      <div className="diffy-pane-head">
        <div className="diffy-pane-title">Fields</div>
        <div className="diffy-pane-sub diffy-mono" style={{ fontSize: 11 }}>
          {endpoint || 'Pick an endpoint'}
        </div>
      </div>
      <div className="diffy-pane-list diffy-tree">
        {!endpoint && (
          <div className="diffy-empty">
            <div>Select an endpoint to view its field tree.</div>
          </div>
        )}
        {endpoint && flat.length === 0 && (
          <div className="diffy-empty">
            <div>No fields match.{excludeNoise && ' Try turning off "Exclude noise".'}</div>
          </div>
        )}
        {flat.map((node) => {
          const leaf = node.name;
          const active = activeField === node.path;
          const intensity = Math.min(1, node.count / maxCount);
          return (
            <div
              key={node.path}
              className={cx('diffy-tree-row', active && 'is-active', node.count === 0 && 'is-clean')}
              style={{ paddingLeft: 8 + node.depth * 14 }}
              onClick={() => onSelectField(node.path)}
            >
              <button
                className="diffy-tree-disclose"
                onClick={(e) => {
                  e.stopPropagation();
                  if (node.hasChildren) toggleOpen(node.path);
                }}
                style={{ visibility: node.hasChildren ? 'visible' : 'hidden' }}
              >
                <Icons.Chevron
                  size={11}
                  style={{
                    transform: openSet.has(node.path) ? 'rotate(90deg)' : 'none',
                    transition: 'transform .12s',
                  }}
                />
              </button>
              <div className="diffy-tree-label">
                <span className="diffy-mono diffy-tree-leaf" title={node.path}>{leaf}</span>
                <span className="diffy-tree-type">{node.type}</span>
                {node.hot && <Tag tone="bad" size="xs">hot</Tag>}
              </div>
              <div className="diffy-tree-meta">
                {node.count > 0 && (
                  <>
                    <div
                      className="diffy-heat"
                      style={{
                        background: `color-mix(in oklch, var(--bad) ${Math.round(intensity * 100)}%, transparent)`,
                        borderColor: intensity > 0.4 ? 'var(--bad)' : 'transparent',
                      }}
                    />
                    <span className="diffy-mono diffy-bad" style={{ fontSize: 11 }}>
                      {node.count}
                    </span>
                  </>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

interface DiffRow {
  id: string;
  requestId: string;
  field: string;
  type: string;
  left: string;
  right: string;
  at: string;
  seenOn: number;
}

function DiffsPane({
  endpoint,
  activeField,
  excludeNoise,
  dateRange,
  onOpenDiff,
}: {
  endpoint?: string;
  activeField?: string;
  excludeNoise: boolean;
  dateRange: { start: number; end: number };
  onOpenDiff: (d: DiffSelection) => void;
}) {
  const args = endpoint && activeField
    ? {
        selectedEndpoint: encodeURIComponent(endpoint),
        selectedFieldPrefix: encodeURIComponent(activeField),
        includeWeights: false,
        excludeNoise,
        start: dateRange.start,
        end: dateRange.end,
      }
    : undefined;
  const res = useFetchDifferencesQuery(args!, { skip: !args });
  const data = res.data as any;

  const rows: DiffRow[] = useMemo(() => {
    if (!data?.requests) return [];
    const out: DiffRow[] = [];
    for (const req of data.requests as any[]) {
      const reqId = req.id as string;
      const diffs = req.differences as Record<string, any> | undefined;
      if (!diffs) continue;
      for (const [rawField, diff] of Object.entries(diffs)) {
        const type = (diff as any).type || 'Difference';
        if (type === 'NoDifference') continue;
        const field = stripTypeMarker(rawField);
        if (activeField && !(field === activeField || field.startsWith(activeField + '.'))) continue;
        out.push({
          id: `${reqId}::${rawField}`,
          requestId: reqId,
          field,
          type,
          left: JSON.stringify((diff as any).left),
          right: JSON.stringify((diff as any).right),
          at: '',
          seenOn: 1,
        });
      }
    }
    return out;
  }, [data]);

  return (
    <div className="diffy-pane diffy-pane-diffs">
      <div className="diffy-pane-head">
        <div className="diffy-pane-title">Diffs</div>
        <div className="diffy-pane-sub">
          {activeField ? (
            <span className="diffy-mono" style={{ fontSize: 11 }}>{activeField}</span>
          ) : (
            <span>Pick a field to inspect</span>
          )}
        </div>
      </div>
      <div className="diffy-pane-list">
        {!activeField && (
          <div className="diffy-empty">
            <div>Select a field on the left to see its diffs.</div>
          </div>
        )}
        {activeField && rows.length === 0 && (
          <div className="diffy-empty">
            <Icons.Pass size={20} />
            <div>No diffs in this scope.</div>
          </div>
        )}
        {rows.map((d) => (
          <div
            key={d.id}
            className="diffy-diff-row"
            onClick={() =>
              onOpenDiff({
                requestId: d.requestId,
                field: d.field,
                endpoint: endpoint || '',
              })
            }
          >
            <div className="diffy-diff-row-head">
              <span className="diffy-mono diffy-text-muted" style={{ fontSize: 11 }}>
                {d.requestId}
              </span>
            </div>
            <div className="diffy-diff-row-field diffy-mono">{d.field}</div>
            <div className="diffy-diff-row-values">
              <div className="diffy-diff-side is-left">
                <span className="diffy-diff-side-tag">primary</span>
                <code>{d.left}</code>
              </div>
              <Icons.ArrowRight size={12} style={{ opacity: 0.35, flexShrink: 0 }} />
              <div className="diffy-diff-side is-right">
                <span className="diffy-diff-side-tag">candidate</span>
                <code>{d.right}</code>
              </div>
            </div>
            <div className="diffy-diff-row-foot">
              <Tag tone={d.type === 'TypeDifference' ? 'bad' : 'default'} size="xs">{d.type}</Tag>
              <div style={{ flex: 1 }} />
              <button className="diffy-link diffy-link-sm">
                Inspect <Icons.Chevron size={11} />
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
