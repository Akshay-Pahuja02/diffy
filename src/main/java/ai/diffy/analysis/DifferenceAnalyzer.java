package ai.diffy.analysis;

import ai.diffy.Settings;
import ai.diffy.compare.Difference;
import ai.diffy.compare.ListComparisonMode;
import ai.diffy.compare.NoDifference;
import ai.diffy.compare.PrimitiveDifference;
import ai.diffy.flat.FlatEntry;
import ai.diffy.flat.FlatObject;
import ai.diffy.lifter.AnalysisRequest;
import ai.diffy.lifter.JsonLifter;
import ai.diffy.lifter.Message;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class DifferenceAnalyzer {

    public static final Logger log = LoggerFactory.getLogger(DifferenceAnalyzer.class);
    public static final Optional<String> UndefinedEndpoint = Optional.of("undefined_endpoint");

    public static String normalizeEndpointName(String name) {
        return name.replace("/", "-");
    }

    private final RawDifferenceCounter rawCounter;
    private final NoiseDifferenceCounter noiseCounter;
    private final InMemoryDifferenceCollector store;
    private final Settings settings;

    public DifferenceAnalyzer(
            RawDifferenceCounter rawCounter,
            NoiseDifferenceCounter noiseCounter,
            InMemoryDifferenceCollector store,
            Settings settings) {
        this.rawCounter   = rawCounter;
        this.noiseCounter = noiseCounter;
        this.store        = store;
        this.settings     = settings;
    }

    public Optional<DifferenceResult> analyze(AnalysisRequest analysisRequest) {
        return apply(
            analysisRequest.request(),
            analysisRequest.candidate(),
            analysisRequest.primary(),
            analysisRequest.secondary(),
            Optional.empty()
        );
    }

    public Optional<DifferenceResult> apply(
            Message request,
            Message candidate,
            Message primary,
            Message secondary) {
        return apply(request, candidate, primary, secondary, Optional.empty());
    }

    public Optional<DifferenceResult> apply(
            Message request,
            Message candidate,
            Message primary,
            Message secondary,
            Optional<String> idKnown) {

        Optional<String> maybeEndpoint = getEndpointName(
            request.endpoint(), candidate.endpoint(),
            primary.endpoint(), secondary.endpoint()
        );

        if (maybeEndpoint.isEmpty()) return Optional.empty();
        String endpointName = maybeEndpoint.get();

        // Request fields — all NoDifference
        Map<String, Difference> requestDiff = new LinkedHashMap<>();
        for (FlatEntry fe : FlatObject.lift(request.result()).rendered()) {
            requestDiff.put("request." + fe.key() + ".NoDifference",
                new NoDifference<>(fe.value()));
        }

        ListComparisonMode mode = settings.listComparisonMode();

        // Raw diff: request fields + response diff (primary vs candidate)
        Map<String, Difference> rawDiff = new LinkedHashMap<>(requestDiff);
        Difference.apply(primary.result(), candidate.result(), mode)
            .flattened().forEach((k, v) -> rawDiff.put("response." + k, v));

        // Noise diff: request fields + response diff (primary vs secondary)
        Map<String, Difference> noiseDiff = new LinkedHashMap<>(requestDiff);
        Difference.apply(primary.result(), secondary.result(), mode)
            .flattened().forEach((k, v) -> noiseDiff.put("response." + k, v));

        String id = idKnown.orElseGet(() -> randomAlphanumeric(10));

        rawCounter.counter().count(endpointName, rawDiff);
        noiseCounter.counter().count(endpointName, noiseDiff);

        boolean hasResponseDiff = rawDiff.entrySet().stream()
            .anyMatch(e -> e.getKey().startsWith("response.") && !(e.getValue() instanceof NoDifference<?>));

        if (hasResponseDiff) {
            Map<String, Difference> actualDiffs = new LinkedHashMap<>();
            rawDiff.forEach((k, v) -> {
                if (k.startsWith("response.") && !(v instanceof NoDifference<?>)) {
                    actualDiffs.put(k, v);
                }
            });
            List<FieldDifference> fieldDiffs = differencesToJson(actualDiffs);
            Date now = new Date();
            DifferenceResult diffResult = new DifferenceResult(
                id,
                Span.current().getSpanContext().getTraceId(),
                endpointName,
                now.getTime(),
                fieldDiffs,
                JsonLifter.encode(request.result()),
                new Responses(
                    JsonLifter.encode(primary.result()),
                    JsonLifter.encode(secondary.result()),
                    JsonLifter.encode(candidate.result())
                ),
                now
            );
            store.create(diffResult);
            return Optional.of(diffResult);
        } else {
            log.debug("endpoint[{}]diff[{}]=NoDifference", endpointName, id);
            return Optional.empty();
        }
    }

    public void clear() {
        rawCounter.counter().clear();
        noiseCounter.counter().clear();
        store.clear();
    }

    public List<FieldDifference> differencesToJson(Map<String, Difference> diffs) {
        return diffs.entrySet().stream().map(entry -> {
            String field = entry.getKey();
            Difference diff = entry.getValue();
            if (diff instanceof PrimitiveDifference<?> pd && pd.left() instanceof Long) {
                Map<String, Object> converted = new LinkedHashMap<>();
                diff.toMap().forEach((k, v) -> converted.put(k, v.toString()));
                return new FieldDifference(field, JsonLifter.encode(converted));
            }
            return new FieldDifference(field, JsonLifter.encode(diff.toMap()));
        }).collect(Collectors.toList());
    }

    private Optional<String> getEndpointName(
            Optional<String> requestEndpoint,
            Optional<String> candidateEndpoint,
            Optional<String> primaryEndpoint,
            Optional<String> secondaryEndpoint) {

        Optional<String> raw;
        if (requestEndpoint.isPresent()) {
            raw = requestEndpoint;
        } else if (candidateEndpoint.isEmpty() && primaryEndpoint.isEmpty() && secondaryEndpoint.isEmpty()) {
            raw = UndefinedEndpoint;
        } else if (candidateEndpoint.isEmpty() && primaryEndpoint.equals(secondaryEndpoint)) {
            raw = primaryEndpoint;
        } else if (candidateEndpoint.isEmpty()) {
            raw = Optional.empty();
        } else {
            raw = candidateEndpoint;
        }

        return raw.map(DifferenceAnalyzer::normalizeEndpointName);
    }

    private static <K, V> Map<K, V> mergeMaps(Map<K, V> base, Map<K, V> extra) {
        Map<K, V> merged = new LinkedHashMap<>(base);
        merged.putAll(extra);
        return merged;
    }

    private static String randomAlphanumeric(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        Random rng = new Random();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(rng.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
