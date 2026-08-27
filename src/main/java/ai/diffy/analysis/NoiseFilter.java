package ai.diffy.analysis;

import ai.diffy.compare.Difference;
import ai.diffy.compare.ListComparisonMode;
import ai.diffy.compare.NoDifference;
import ai.diffy.lifter.JsonLifter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Filters field-level diffs using primary vs secondary (noise) baseline. */
public final class NoiseFilter {

    private NoiseFilter() {}

    public static boolean isActualDiff(Difference diff) {
        return diff != null && !(diff instanceof NoDifference<?>);
    }

    public static Map<String, Difference> noiseDiffMap(Object primary, Object secondary) {
        return noiseDiffMap(primary, secondary, ai.diffy.compare.ListComparisonMode.LEGACY);
    }

    public static Map<String, Difference> noiseDiffMap(
            Object primary,
            Object secondary,
            ListComparisonMode mode) {
        return Difference.apply(primary, secondary, mode).flattened();
    }

    /** True when primary vs secondary also differs at the same response field path. */
    public static boolean isNoiseField(String rawFieldPath, Map<String, Difference> noiseByPath) {
        if (noiseByPath == null) return false;
        Difference atPath = noiseByPath.get(rawFieldPath);
        if (atPath != null) {
            return isActualDiff(atPath);
        }
        String stripped = stripTypeSuffix(rawFieldPath);
        for (Map.Entry<String, Difference> e : noiseByPath.entrySet()) {
            if (stripTypeSuffix(e.getKey()).equals(stripped)) {
                return isActualDiff(e.getValue());
            }
        }
        return false;
    }

    public static boolean passesThreshold(JoinedField field, Predicate<JoinedField> thresholdFilter) {
        return thresholdFilter.test(field);
    }

    public static boolean excludeAsNoise(
            String rawFieldPath,
            JoinedField joinedField,
            Map<String, Difference> noiseByPath,
            Predicate<JoinedField> thresholdFilter,
            java.util.List<String> manualNoisePrefixes) {
        if (manualNoisePrefixes != null) {
            for (String prefix : manualNoisePrefixes) {
                if (rawFieldPath.startsWith(prefix)) return true;
            }
        }
        if (joinedField != null && !passesThreshold(joinedField, thresholdFilter)) {
            return true;
        }
        return isNoiseField(rawFieldPath, noiseByPath);
    }

    public static String stripTypeSuffix(String path) {
        return path.replaceFirst(
            "\\.(NoDifference|PrimitiveDifference|TypeDifference|ObjectDifference|SeqDifference|"
                + "SeqSizeDifference|SetDifference|MapDifference|OrderingDifference|IndexedDifference|"
                + "TerminalDifference|ExtraField|MissingField)$",
            ""
        );
    }

    public static Map<String, Object> filterDifferenceMap(
            Map<String, Object> differences,
            Map<String, Difference> noiseByPath,
            Map<String, JoinedField> joinedFields,
            Predicate<JoinedField> thresholdFilter,
            java.util.List<String> manualNoisePrefixes) {
        Map<String, Object> out = new LinkedHashMap<>();
        differences.forEach((field, diffObj) -> {
            if (diffObj instanceof Map<?, ?> m && "NoDifference".equals(m.get("type"))) {
                return;
            }
            JoinedField jf = joinedFields != null ? joinedFields.get(field) : null;
            if (excludeAsNoise(field, jf, noiseByPath, thresholdFilter, manualNoisePrefixes)) {
                return;
            }
            out.put(field, diffObj);
        });
        return out;
    }

    public static Map<String, Difference> decodeNoiseFromStored(DifferenceResult dr, ListComparisonMode mode) {
        Object primary = JsonLifter.decode(dr.responses.primary);
        Object secondary = JsonLifter.decode(dr.responses.secondary);
        Map<String, Difference> flat = noiseDiffMap(primary, secondary, mode);
        Map<String, Difference> prefixed = new LinkedHashMap<>();
        flat.forEach((k, v) -> prefixed.put("response." + k, v));
        return prefixed;
    }
}
