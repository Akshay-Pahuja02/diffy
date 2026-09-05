package ai.diffy.analysis;

import ai.diffy.lifter.FieldMap;

/**
 * Default {@link DynamicAnalyzer} for JSON APIs. Stored payloads are JSON-encoded {@link FieldMap}s,
 * so decoding is a straight JSON parse. This preserves the original Diffy behavior and acts as the
 * universal fallback for any non-proto content type.
 */
public class JSONDynamicAnalyzer extends AbstractDynamicAnalyzer {

    @Override
    public boolean supports(String contentType) {
        return true;
    }

    @Override
    public FieldMap decodeResponseFieldMap(String payload, String requestPath) {
        return decodeJsonFieldMap(payload);
    }
}
