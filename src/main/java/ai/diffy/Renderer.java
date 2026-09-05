package ai.diffy;

import ai.diffy.analysis.*;
import ai.diffy.lifter.JsonLifter;

import java.util.*;

public class Renderer {

    public static Map<String, Object> differences(Iterable<FieldDifference> diffs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (FieldDifference fd : diffs) {
            result.put(fd.field, JsonLifter.decode(fd.difference));
        }
        return result;
    }

    public static Iterable<Map<String, Object>> differenceResults(
            Iterable<DifferenceResult> drs,
            boolean includeRequestResponses) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DifferenceResult dr : drs) {
            result.add(differenceResult(dr, includeRequestResponses));
        }
        return result;
    }

    public static Iterable<Map<String, Object>> differenceResults(Iterable<DifferenceResult> drs) {
        return differenceResults(drs, false);
    }

    public static Map<String, Object> differenceResult(DifferenceResult dr, boolean includeRequestResponses) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",              dr.id);
        m.put("trace_id",        dr.traceId);
        m.put("timestamp_msec",  dr.timestampMsec);
        m.put("endpoint",        dr.endpoint);
        m.put("differences",     differences(dr.differences));
        if (includeRequestResponses) {
            m.put("request", JsonLifter.decode(dr.request));
            m.put("left",    JsonLifter.decode(dr.responses.primary));
            m.put("secondary", JsonLifter.decode(dr.responses.secondary));
            m.put("right",   JsonLifter.decode(dr.responses.candidate));
        }
        return m;
    }

    public static Map<String, Object> differenceResult(DifferenceResult dr) {
        return differenceResult(dr, false);
    }

    public static Map<String, Map<String, Integer>> endpoints(Map<String, EndpointMetadata> endpoints) {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        endpoints.forEach((ep, meta) -> result.put(ep, endpoint(meta)));
        return result;
    }

    public static Map<String, Integer> endpoint(EndpointMetadata ep) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("total",       ep.total());
        m.put("differences", ep.differences());
        return m;
    }

    public static Map<String, Object> field(FieldMetadata field, boolean includeWeight) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("differences", field.differences());
        if (includeWeight) m.put("weight", field.weight());
        return m;
    }

    public static Map<String, Object> field(JoinedField field, boolean includeWeight) {
        Map<String, Object> m = new LinkedHashMap<>();
        int raw = field.raw().differences();
        int noiseCount = field.noise().differences();
        int signal = Math.max(0, raw - noiseCount);
        m.put("differences", raw);
        m.put("noise",       noiseCount);
        m.put("signal",      signal);
        m.put("relative_difference", field.relativeDifference());
        m.put("absolute_difference", field.absoluteDifference());
        if (includeWeight) m.put("weight", field.raw().weight());
        return m;
    }

    public static Map<String, Object> fields(Map<String, JoinedField> fields, boolean includeWeight) {
        Map<String, Object> result = new LinkedHashMap<>();
        fields.forEach((path, meta) -> result.put(path, field(meta, includeWeight)));
        return result;
    }

    public static Map<String, Object> fields(Map<String, JoinedField> fields) {
        return fields(fields, false);
    }

    public static Map<String, String> error(String message) {
        return Map.of("error", message);
    }

    public static Map<String, String> success(String message) {
        return Map.of("success", message);
    }
}
