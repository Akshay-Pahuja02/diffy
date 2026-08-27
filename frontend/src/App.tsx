import { useEffect, useMemo, useState } from 'react';
import './styles/diffy.css';

import { useAppDispatch, useAppSelector } from './app/hooks';
import {
  closeInspector,
  openInspector,
  selectEndpoint,
  selectFieldPrefix,
  setRunId,
  setSearch,
  setView,
  toggleNoiseCancellation,
} from './features/selections/selectionsSlice';
import { Sidebar, Topbar } from './diffy/Shell';
import { OverviewView, EndpointSummary } from './diffy/views/OverviewView';
import { EndpointsWorkspace } from './diffy/views/EndpointsWorkspace';
import { NoiseView } from './diffy/views/NoiseView';
import { TransformationsView } from './diffy/views/TransformationsView';
import { RunsView } from './diffy/views/RunsView';
import { DiffInspector } from './diffy/views/DiffInspector';
import { useFetchRunsQuery } from './features/runs/runsApiSlice';
import { useFetchEndpointsQuery } from './features/noise/noiseApiSlice';

function App() {
  const dispatch = useAppDispatch();
  const selections = useAppSelector((s) => s.selections);
  const { runId, view, endpointName, fieldPrefix, inspectorDiff, noiseCancellationIsOn, search, dateTimeRange } =
    selections;

  const runsRes = useFetchRunsQuery(undefined, { pollingInterval: 10_000 });
  const runs = useMemo(() => runsRes.data || [], [runsRes.data]);
  const activeRunId = runs.find((r) => r.id === runId)?.id || runs[0]?.id || 'current';
  const activeRun = runs.find((r) => r.id === activeRunId);

  useEffect(() => {
    if (runs.length && !runs.find((r) => r.id === runId)) {
      dispatch(setRunId(runs[0].id));
    }
  }, [runs, runId, dispatch]);

  const epsRes = useFetchEndpointsQuery(
    {
      excludeNoise: noiseCancellationIsOn,
      start: dateTimeRange.start,
      end: dateTimeRange.end,
    },
    { pollingInterval: 10_000 },
  );
  const endpointSummaries: EndpointSummary[] = useMemo(() => {
    const eps = (epsRes.data as Record<string, { total: number; differences: number }>) || {};
    return Object.entries(eps).map(([path, m]) => {
      const total = m.total || 0;
      const diffs = m.differences || 0;
      return {
        path,
        requests: total,
        diffs,
        diffRate: total > 0 ? (diffs / total) * 100 : 0,
      };
    });
  }, [epsRes.data]);

  // Default endpoint selection
  useEffect(() => {
    if (!endpointName && endpointSummaries.length) {
      dispatch(selectEndpoint(endpointSummaries[0].path));
    }
  }, [endpointSummaries, endpointName, dispatch]);

  const endpointBadge = endpointSummaries.filter((e) => e.diffs > 0).length;
  const [inspectorLayout, setInspectorLayout] = useState<'split' | 'threeway'>('split');

  const openEndpoint = (path: string) => {
    dispatch(selectEndpoint(path));
    dispatch(setView('endpoints'));
  };

  return (
    <div className="diffy-root">
      <Sidebar
        runs={runs}
        activeRunId={activeRunId}
        onPickRun={(id) => dispatch(setRunId(id))}
        activeView={view}
        onPickView={(v) => dispatch(setView(v))}
        endpointBadge={endpointBadge}
      />
      <main className="diffy-main">
        <Topbar
          activeRun={activeRun}
          activeView={view}
          search={search}
          onSearch={(s) => dispatch(setSearch(s))}
        />
        <div className="diffy-scroll">
          {view === 'overview' && activeRun && (
            <OverviewView
              run={activeRun}
              endpoints={endpointSummaries}
              onOpenEndpoint={openEndpoint}
              onOpenView={(v) => dispatch(setView(v))}
            />
          )}
          {view === 'endpoints' && (
            <EndpointsWorkspace
              endpoints={endpointSummaries}
              selected={endpointName}
              onSelectEndpoint={(p) => dispatch(selectEndpoint(p))}
              activeField={fieldPrefix}
              onSelectField={(p) => dispatch(selectFieldPrefix(p))}
              onOpenDiff={(d) => dispatch(openInspector(d))}
              excludeNoise={noiseCancellationIsOn}
              onToggleNoise={() => dispatch(toggleNoiseCancellation())}
              dateRange={dateTimeRange}
            />
          )}
          {view === 'noise' && (
            <NoiseView endpoint={endpointName} dateRange={dateTimeRange} />
          )}
          {view === 'transforms' && <TransformationsView />}
          {view === 'runs' && (
            <RunsView
              runs={runs}
              activeRunId={activeRunId}
              onPickRun={(id) => {
                dispatch(setRunId(id));
                dispatch(setView('overview'));
              }}
            />
          )}
        </div>
      </main>

      {inspectorDiff && (
        <DiffInspector
          selection={inspectorDiff}
          onClose={() => dispatch(closeInspector())}
          layout={inspectorLayout}
          onLayoutChange={setInspectorLayout}
        />
      )}
    </div>
  );
}

export default App;
