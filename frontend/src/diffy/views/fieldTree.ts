import { Metric } from '../../features/fields/Metric';

// Backend metric leaves have these numeric keys (and may add `name`/`weight` when include_weights=true).
const METRIC_KEYS = ['differences', 'noise', 'signal', 'relative_difference', 'absolute_difference'];

function isMetricLeaf(v: any): v is Metric {
  if (!v || typeof v !== 'object' || Array.isArray(v)) return false;
  return METRIC_KEYS.every((k) => typeof v[k] === 'number');
}

// Diffy backend tags every field path with a trailing class-name segment:
// NoDifference, PrimitiveDifference, TypeDifference, ObjectDifference, SeqDifference,
// SeqSizeDifference, SetDifference, MapDifference, OrderingDifference, IndexedDifference,
// TerminalDifference, ExtraField, MissingField. Strip that suffix for display & state.
const TYPE_MARKER_RE = /\.(NoDifference|PrimitiveDifference|TypeDifference|ObjectDifference|SeqDifference|SeqSizeDifference|SetDifference|MapDifference|OrderingDifference|IndexedDifference|TerminalDifference|ExtraField|MissingField)$/;

export function stripTypeMarker(path: string): string {
  return path.replace(TYPE_MARKER_RE, '');
}

export function extractTypeMarker(path: string): string {
  const m = path.match(TYPE_MARKER_RE);
  return m ? m[1] : 'leaf';
}

export interface FieldTreeNode {
  path: string;
  name: string;
  count: number;
  type: string;
  suspectNoise?: boolean;
  hot?: boolean;
  children?: FieldTreeNode[];
}

interface BuildEntry {
  path: string;
  type: string;
  metric: Metric;
}

function displayCount(metric: Metric): number {
  if (typeof metric.signal === 'number' && metric.signal > 0) return metric.signal;
  if (typeof metric.differences === 'number' && metric.differences > 0) return metric.differences;
  return 0;
}

// The backend returns either:
//  - nested objects (mocked design): { request: { method: { differences, noise, ... } } }
//  - flat dot-paths with type suffix: { "request.method.NoDifference": { differences, noise, ... } }
// We normalize to a list of { path, type, metric } then assemble a tree.
function normalize(fields: Record<string, any>): BuildEntry[] {
  const out: BuildEntry[] = [];
  function visit(node: any, prefix: string) {
    if (isMetricLeaf(node)) {
      out.push({ path: prefix, type: extractTypeMarker(prefix).replace(/Difference$/i, '').toLowerCase() || 'leaf', metric: node });
      return;
    }
    if (node && typeof node === 'object') {
      for (const [k, v] of Object.entries(node)) {
        if (isMetricLeaf(v)) {
          const fullKey = prefix ? `${prefix}.${k}` : k;
          const realPath = stripTypeMarker(fullKey);
          if (realPath.startsWith('request.')) continue;
          const type = extractTypeMarker(fullKey).replace(/Difference$/i, '').toLowerCase() || 'leaf';
          const count = displayCount(v as Metric);
          if (count <= 0) continue;
          out.push({ path: realPath, type, metric: v as Metric });
        } else {
          visit(v, prefix ? `${prefix}.${k}` : k);
        }
      }
    }
  }
  visit(fields, '');
  return out;
}

function assemble(entries: BuildEntry[]): FieldTreeNode[] {
  const root: { children: Map<string, any>; node?: FieldTreeNode } = { children: new Map() };

  function getOrCreate(parts: string[], parent = root): any {
    let cur = parent;
    let accum = '';
    for (const p of parts) {
      accum = accum ? `${accum}.${p}` : p;
      if (!cur.children.has(p)) {
        cur.children.set(p, { children: new Map(), node: { path: accum, name: p, count: 0, type: 'object' } });
      }
      cur = cur.children.get(p);
    }
    return cur;
  }

  for (const e of entries) {
    const parts = e.path.split('.');
    const leaf = getOrCreate(parts);
    const count = displayCount(e.metric);
    leaf.node = {
      path: e.path,
      name: parts[parts.length - 1] || e.path,
      count,
      type: e.type,
      hot: count > 10,
    };
  }

  function finalize(holder: any): FieldTreeNode | null {
    const children = Array.from(holder.children.values())
      .map(finalize)
      .filter((n): n is FieldTreeNode => n != null);
    const node: FieldTreeNode = holder.node || { path: '', name: '', count: 0, type: 'object' };
    if (children.length) {
      node.children = children;
      node.count = Math.max(...children.map((c) => c.count), 0);
      node.hot = node.hot || node.count > 10;
      node.type = node.type === 'object' ? 'object' : node.type;
    }
    if (node.count <= 0 && !children.length) return null;
    return node;
  }

  return Array.from(root.children.values())
    .map(finalize)
    .filter((n): n is FieldTreeNode => n != null);
}

export function buildFieldTree(fields: Record<string, any> | undefined): FieldTreeNode[] {
  if (!fields) return [];
  return assemble(normalize(fields));
}

export function maxFieldCount(nodes: FieldTreeNode[]): number {
  let m = 0;
  const walk = (ns: FieldTreeNode[]) =>
    ns.forEach((n) => {
      if (n.count > m) m = n.count;
      if (n.children) walk(n.children);
    });
  walk(nodes);
  return m || 1;
}

/** @deprecated Backend exclude_noise handles P vs S filtering; kept for empty fallback. */
export function hideNoise(nodes: FieldTreeNode[]): FieldTreeNode[] {
  return nodes;
}

export interface FlatNode extends FieldTreeNode {
  depth: number;
  hasChildren: boolean;
}

export function flatten(nodes: FieldTreeNode[], openSet: Set<string>, depth = 0, out: FlatNode[] = []): FlatNode[] {
  for (const n of nodes) {
    out.push({ ...n, depth, hasChildren: !!(n.children && n.children.length) });
    if (n.children && openSet.has(n.path)) flatten(n.children, openSet, depth + 1, out);
  }
  return out;
}
