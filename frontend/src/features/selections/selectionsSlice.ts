import { createSlice, PayloadAction } from '@reduxjs/toolkit';

export type View = 'overview' | 'endpoints' | 'noise' | 'transforms' | 'runs';

export interface DiffSelection {
  requestId: string;
  field: string;
  endpoint: string;
}

interface Selections {
  runId: string;
  view: View;
  noiseCancellationIsOn: boolean;
  endpointName: string | undefined;
  fieldPrefix: string | undefined;
  inspectorDiff: DiffSelection | undefined;
  search: string;
  dateTimeRange: { start: number; end: number };
}

const initialState: Selections = {
  runId: 'current',
  view: 'overview',
  noiseCancellationIsOn: true,
  endpointName: undefined,
  fieldPrefix: undefined,
  inspectorDiff: undefined,
  search: '',
  // Match chart window; API default end=1701001001000 truncates older data if start is too recent
  dateTimeRange: { start: 0, end: 9_999_999_999_999 },
};

const slice = createSlice({
  name: 'selections',
  initialState,
  reducers: {
    setRunId(state, action: PayloadAction<string>) {
      state.runId = action.payload;
      state.endpointName = undefined;
      state.fieldPrefix = undefined;
    },
    setView(state, action: PayloadAction<View>) {
      state.view = action.payload;
    },
    toggleNoiseCancellation(state) {
      state.noiseCancellationIsOn = !state.noiseCancellationIsOn;
    },
    selectEndpoint(state, action: PayloadAction<string | undefined>) {
      state.endpointName = action.payload;
      state.fieldPrefix = undefined;
    },
    selectFieldPrefix(state, action: PayloadAction<string | undefined>) {
      state.fieldPrefix = action.payload;
    },
    openInspector(state, action: PayloadAction<DiffSelection>) {
      state.inspectorDiff = action.payload;
    },
    closeInspector(state) {
      state.inspectorDiff = undefined;
    },
    setSearch(state, action: PayloadAction<string>) {
      state.search = action.payload;
    },
    setDateTimeRange(state, action: PayloadAction<{ start: number; end: number }>) {
      state.dateTimeRange = action.payload;
    },
  },
});

export const {
  setRunId,
  setView,
  toggleNoiseCancellation,
  selectEndpoint,
  selectFieldPrefix,
  openInspector,
  closeInspector,
  setSearch,
  setDateTimeRange,
} = slice.actions;

export default slice.reducer;
