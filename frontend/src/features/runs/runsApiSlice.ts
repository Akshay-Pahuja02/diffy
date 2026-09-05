import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react';

export interface Run {
  id: string;
  label: string;
  status: 'running' | 'done';
  verdict: 'pass' | 'fail' | null;
  startedAt: string;
  duration: string;
  totalReqs: number;
  realDiffs: number;
  noiseDiffs: number;
  diffRate: number;
  noiseRate: number;
  candidate: string;
  primary: string;
  secondary: string;
  author: string;
  branch: string;
}

export interface RawInfo {
  name: string;
  primary: { target: string };
  secondary: { target: string };
  candidate: { target: string };
  relativeThreshold: number;
  absoluteThreshold: number;
  last_reset: number;
  protocol: string;
}

export interface RawEndpointMeta {
  total: number;
  differences: number;
}

function fmtAgo(millisAgo: number): string {
  const m = Math.floor(millisAgo / 60_000);
  if (m < 1) return 'just now';
  if (m < 60) return `${m} min ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.floor(h / 24);
  if (d === 1) return 'yesterday';
  return `${d}d ago`;
}

function fmtDuration(ms: number): string {
  if (ms < 0) ms = 0;
  const totalSec = Math.floor(ms / 1000);
  const hh = Math.floor(totalSec / 3600).toString().padStart(2, '0');
  const mm = Math.floor((totalSec % 3600) / 60).toString().padStart(2, '0');
  const ss = (totalSec % 60).toString().padStart(2, '0');
  return `${hh}:${mm}:${ss}`;
}

export function synthesizeRun(
  info: RawInfo,
  endpoints: Record<string, RawEndpointMeta>,
  noiseDiffs: number,
  now: number = Date.now(),
): Run {
  const startedAtMillis = info.last_reset > 0 ? info.last_reset : now;
  const eps = Object.values(endpoints);
  const totalReqs = eps.reduce((s, e) => s + (e.total || 0), 0);
  const realDiffs = eps.reduce((s, e) => s + (e.differences || 0), 0);
  const diffRate = totalReqs > 0 ? (realDiffs / totalReqs) * 100 : 0;
  const noiseRate = totalReqs > 0 ? (noiseDiffs / totalReqs) * 100 : 0;
  const verdict: Run['verdict'] = realDiffs === 0 ? 'pass' : 'fail';
  return {
    id: `r-${Math.floor(startedAtMillis / 1000)}`,
    label: info.name,
    status: 'running',
    verdict: null,
    startedAt: fmtAgo(now - startedAtMillis),
    duration: fmtDuration(now - startedAtMillis),
    totalReqs,
    realDiffs,
    noiseDiffs,
    diffRate,
    noiseRate,
    candidate: info.candidate.target,
    primary: info.primary.target,
    secondary: info.secondary.target,
    author: 'local',
    branch: 'current',
    // verdict only matters when status === 'done'; keep null so UI shows running
    ...(verdict ? {} : {}),
  };
}

export const apiRunsSlice = createApi({
  reducerPath: 'runsApi',
  tagTypes: ['Runs'],
  baseQuery: fetchBaseQuery({ baseUrl: '/api/1' }),
  endpoints(builder) {
    return {
      fetchRuns: builder.query<Run[], void>({
        async queryFn(_arg, _api, _extraOptions, fetchWithBQ) {
          const infoRes = await fetchWithBQ('/info');
          if (infoRes.error) return { error: infoRes.error };
          const epRes = await fetchWithBQ('/endpoints?exclude_noise=false&start=0&end=9999999999999');
          if (epRes.error) return { error: epRes.error };
          const epNoiseRes = await fetchWithBQ('/endpoints?exclude_noise=true&start=0&end=9999999999999');
          if (epNoiseRes.error) return { error: epNoiseRes.error };

          const info = infoRes.data as RawInfo;
          const allEps = (epRes.data as Record<string, RawEndpointMeta>) || {};
          const cleanEps = (epNoiseRes.data as Record<string, RawEndpointMeta>) || {};

          const realDiffs = Object.values(cleanEps).reduce((s, e) => s + (e.differences || 0), 0);
          const totalDiffs = Object.values(allEps).reduce((s, e) => s + (e.differences || 0), 0);
          const noiseDiffs = Math.max(0, totalDiffs - realDiffs);

          // Total counts from all endpoints; realDiffs from noise-filtered subset
          const run = synthesizeRun(info, allEps, noiseDiffs);
          run.realDiffs = realDiffs;
          return { data: [run] };
        },
        providesTags: ['Runs'],
      }),
    };
  },
});

export const { useFetchRunsQuery } = apiRunsSlice;
