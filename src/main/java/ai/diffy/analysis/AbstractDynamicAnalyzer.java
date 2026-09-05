package ai.diffy.analysis;

import ai.diffy.Settings;
import ai.diffy.lifter.FieldMap;
import ai.diffy.lifter.JsonLifter;
import ai.diffy.lifter.Message;
import ai.diffy.repository.DifferenceResultRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Shared decoding utilities and the replay driver for {@link DynamicAnalyzer} implementations.
 *
 * <p>The replay loop is format-agnostic: it decodes the request (always HTTP metadata encoded as
 * JSON), reads its content-type header, and lets the caller select the response adaptor per record.
 * This keeps the original Diffy comparison pipeline intact while allowing proto and JSON payloads to
 * coexist in the same store.
 */
public abstract class AbstractDynamicAnalyzer implements DynamicAnalyzer {

    public static FieldMap objectNodeToFieldMap(ObjectNode objectNode) {
        Map<String, Object> acc = new LinkedHashMap<>();
        objectNode.fields().forEachRemaining(entry ->
            acc.put(entry.getKey(), entry.getValue())
        );
        if (acc.containsKey("headers")) {
            acc.put("headers", objectNodeToFieldMap((ObjectNode) acc.get("headers")));
        }
        return new FieldMap(acc);
    }

    public static FieldMap decodeJsonFieldMap(String payload) {
        return objectNodeToFieldMap((ObjectNode) JsonLifter.decode(payload));
    }

    /** Reads the content-type header from a decoded request {@link FieldMap} (case-insensitive). */
    public static String contentTypeOf(FieldMap request) {
        Object headers = request.value.get("headers");
        if (!(headers instanceof FieldMap headerMap)) {
            return null;
        }
        for (Map.Entry<String, Object> entry : headerMap.value.entrySet()) {
            if ("content-type".equalsIgnoreCase(entry.getKey())) {
                return asText(entry.getValue());
            }
        }
        return null;
    }

    /** Reads the request path (falling back to the full uri) from a decoded request {@link FieldMap}. */
    public static String pathOf(FieldMap request) {
        String path = asText(request.value.get("path"));
        return path != null ? path : asText(request.value.get("uri"));
    }

    private static String asText(Object value) {
        if (value instanceof JsonNode node) {
            return node.asText();
        }
        return value == null ? null : value.toString();
    }

    /**
     * Replays stored diffs in the time window through a fresh {@link DifferenceAnalyzer}. For each
     * record, {@code selector} chooses the response adaptor based on the request's content-type
     * header. Request payloads are always decoded as JSON HTTP metadata.
     */
    public static Report replay(
            DifferenceResultRepository repository,
            long start,
            long end,
            Function<String, DynamicAnalyzer> selector,
            Settings settings) {

        InMemoryDifferenceCollector collector = new InMemoryDifferenceCollector();
        RawDifferenceCounter raw   = new RawDifferenceCounter(InMemoryDifferenceCollector.newCounter("raw"));
        NoiseDifferenceCounter noise = new NoiseDifferenceCounter(InMemoryDifferenceCollector.newCounter("noise"));
        JoinedDifferences joinedDifferences = new JoinedDifferences(raw, noise);
        DifferenceAnalyzer analyzer = new DifferenceAnalyzer(raw, noise, collector, settings);

        repository.findByTimestampMsecBetween(start, end).forEach(dr -> {
            FieldMap requestFieldMap = decodeJsonFieldMap(dr.request);
            DynamicAnalyzer selected = selector.apply(contentTypeOf(requestFieldMap));
            String requestPath = pathOf(requestFieldMap);
            Optional<String> endpoint = Optional.of(dr.endpoint);

            Message request   = new Message(endpoint, requestFieldMap);
            Message primary   = new Message(endpoint, selected.decodeResponseFieldMap(dr.responses.primary, requestPath));
            Message secondary = new Message(endpoint, selected.decodeResponseFieldMap(dr.responses.secondary, requestPath));
            Message candidate = new Message(endpoint, selected.decodeResponseFieldMap(dr.responses.candidate, requestPath));
            analyzer.apply(request, candidate, primary, secondary, Optional.of(dr.id));
        });

        return new Report(analyzer, joinedDifferences, collector, start, end);
    }
}
