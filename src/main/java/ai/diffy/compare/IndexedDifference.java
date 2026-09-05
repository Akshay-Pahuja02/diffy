package ai.diffy.compare;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record IndexedDifference(List<Difference> indexedDiffs) implements SeqDifference {
    @Override
    public Map<String, Difference> flattened() {
        Map<String, Difference> result = new LinkedHashMap<>();
        for (int i = 0; i < indexedDiffs.size(); i++) {
            String prefix = i + ".";
            for (Map.Entry<String, Difference> e : indexedDiffs.get(i).flattened().entrySet()) {
                result.put(prefix + e.getKey(), e.getValue());
            }
        }
        return result;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "IndexedDifference");
        Map<Integer, Object> children = new LinkedHashMap<>();
        for (int i = 0; i < indexedDiffs.size(); i++) {
            children.put(i, indexedDiffs.get(i).toMap());
        }
        m.put("children", children);
        return m;
    }
}
