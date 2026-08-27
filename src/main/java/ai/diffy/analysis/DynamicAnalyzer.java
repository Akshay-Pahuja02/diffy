package ai.diffy.analysis;

import ai.diffy.lifter.FieldMap;

/**
 * Adaptor abstraction over the response payload format of a compared API.
 *
 * <p>The core Diffy comparison logic is unchanged across implementations; the only thing that varies
 * is how a stored response payload is turned back into a {@link FieldMap}. Implementations are chosen
 * per record based on the request's content-type header (see
 * {@link AbstractDynamicAnalyzer#replay}). See {@link JSONDynamicAnalyzer} (JSON payloads) and
 * {@link ProtoDynamicAnalyzer} (protobuf payloads).
 */
public interface DynamicAnalyzer {

    /** Whether this analyzer handles responses for a request declaring the given content type. */
    boolean supports(String contentType);

    /**
     * Adaptor: convert a stored response payload into a {@link FieldMap} for comparison. The request
     * path is provided so per-URI configuration (e.g. the proto message class) can be resolved.
     */
    FieldMap decodeResponseFieldMap(String payload, String requestPath);
}
