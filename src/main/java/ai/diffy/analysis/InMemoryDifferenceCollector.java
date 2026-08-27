package ai.diffy.analysis;

import ai.diffy.compare.Difference;
import ai.diffy.compare.ListComparisonMode;
import ai.diffy.compare.NoDifference;
import ai.diffy.metrics.MetricsReceiver;
import io.micrometer.core.instrument.Counter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

// ============================================================================
// InMemoryFieldMetadata
// ============================================================================
class InMemoryFieldMetadata implements FieldMetadata {
    private final Counter differenceCounter;
    private final Counter siblingsCounter;
    private final AtomicInteger diffsAtomic  = new AtomicInteger(0);
    private final AtomicInteger sibsAtomic   = new AtomicInteger(0);

    InMemoryFieldMetadata(MetricsReceiver receiver) {
        this.differenceCounter = receiver.withNameToken("differences").counter();
        this.siblingsCounter   = receiver.withNameToken("siblings").counter();
    }

    @Override public int differences() { return diffsAtomic.get(); }
    @Override public int weight()      { return sibsAtomic.get(); }

    void increment() {
        differenceCounter.increment();
        diffsAtomic.incrementAndGet();
    }
}

// ============================================================================
// InMemoryEndpointMetadata
// ============================================================================
class InMemoryEndpointMetadata implements EndpointMetadata {
    private final Counter totalCounter;
    private final Counter differenceCounter;
    private final AtomicInteger totalAtomic      = new AtomicInteger(0);
    private final AtomicInteger differenceAtomic = new AtomicInteger(0);
    private final MetricsReceiver receiver;

    private final Map<String, InMemoryFieldMetadata> _fields = new ConcurrentHashMap<>();

    InMemoryEndpointMetadata(MetricsReceiver receiver) {
        this.receiver          = receiver;
        this.totalCounter      = receiver.withNameToken("all").counter();
        this.differenceCounter = receiver.withNameToken("different").counter();
    }

    @Override public int total()       { return totalAtomic.get(); }
    @Override public int differences() { return differenceAtomic.get(); }

    InMemoryFieldMetadata getMetadata(String field) {
        return _fields.computeIfAbsent(field,
            f -> new InMemoryFieldMetadata(receiver.withAdditionalTags(Map.of("field", f))));
    }

    Map<String, InMemoryFieldMetadata> fields() {
        return Collections.unmodifiableMap(_fields);
    }

    void add(Map<String, Difference> diffs) {
        try { totalCounter.increment(); } catch (Exception ignored) {}
        totalAtomic.incrementAndGet();

        boolean hasDiffs = diffs.entrySet().stream()
            .anyMatch(e -> !(e.getValue() instanceof NoDifference<?>));
        if (hasDiffs) {
            differenceCounter.increment();
            differenceAtomic.incrementAndGet();
        }
        diffs.forEach((fieldPath, diff) -> {
            if (!(diff instanceof NoDifference<?>)) {
                getMetadata(fieldPath).increment();
            }
        });
    }
}

// ============================================================================
// InMemoryDifferenceCounter implements DifferenceCounter
// ============================================================================
class InMemoryDifferenceCounter implements DifferenceCounter {
    private final MetricsReceiver receiver;
    private final Map<String, InMemoryEndpointMetadata> endpointsMap = new ConcurrentHashMap<>();

    InMemoryDifferenceCounter(String name) {
        this.receiver = MetricsReceiver.root().withNameToken(name);
    }

    private InMemoryEndpointMetadata endpointCollector(String ep) {
        return endpointsMap.computeIfAbsent(ep,
            k -> new InMemoryEndpointMetadata(receiver.withAdditionalTags(Map.of("endpoint", k))));
    }

    @Override
    public Map<String, EndpointMetadata> endpoints() {
        return endpointsMap.entrySet().stream()
            .filter(e -> e.getValue().total() > 0)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public void clear() { endpointsMap.clear(); }

    @Override
    public Map<String, FieldMetadata> fields(String ep) {
        return new HashMap<>(endpointCollector(ep).fields());
    }

    @Override
    public void count(String endpoint, Map<String, Difference> diffs) {
        endpointCollector(endpoint).add(diffs);
    }
}

// ============================================================================
// InMemoryDifferenceCollector
// ============================================================================
public class InMemoryDifferenceCollector {

    public static final Exception DifferenceResultNotFoundException =
        new Exception("Difference result not found");

    private final int requestsPerField = 5;
    private final Map<Field, Deque<DifferenceResult>> fields = new LinkedHashMap<>();

    private static String sanitizePath(String p) {
        String s = p;
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.startsWith("/")) s = s.substring(1);
        return s;
    }

    public void create(DifferenceResult dr) {
        dr.differences.forEach(fieldDifference -> {
            String path = fieldDifference.field;
            Field key = new Field(dr.endpoint, sanitizePath(path));
            fields.computeIfAbsent(key, k -> new ArrayDeque<>());
            Deque<DifferenceResult> queue = fields.get(key);
            if (queue.size() < requestsPerField) {
                queue.addLast(dr);
            }
        });
    }

    public List<DifferenceResult> prefix(Field field) {
        List<DifferenceResult> result = new ArrayList<>();
        for (Map.Entry<Field, Deque<DifferenceResult>> entry : fields.entrySet()) {
            Field f = entry.getKey();
            if (f.endpoint().equals(field.endpoint()) && f.prefix().startsWith(field.prefix())) {
                result.addAll(entry.getValue());
            }
        }
        // distinct by id
        Map<String, DifferenceResult> seen = new LinkedHashMap<>();
        result.forEach(dr -> seen.put(dr.id, dr));
        return new ArrayList<>(seen.values());
    }

    public DifferenceResult apply(String id) throws Exception {
        for (Deque<DifferenceResult> queue : fields.values()) {
            for (DifferenceResult dr : queue) {
                if (dr.id.equals(id)) return dr;
            }
        }
        throw DifferenceResultNotFoundException;
    }

    public void clear() { fields.clear(); }

    // Package-visible factory for InMemoryDifferenceCounter (used by DynamicAnalyzer)
    public static DifferenceCounter newCounter(String name) {
        return new InMemoryDifferenceCounter(name);
    }
}
