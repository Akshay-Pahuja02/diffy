package ai.diffy.analysis;

import ai.diffy.lifter.FieldMap;
import ai.diffy.lifter.ProtoConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;

/**
 * {@link DynamicAnalyzer} for HTTP/1.1 APIs that use protobuf for their responses.
 *
 * <p>The comparison logic is unchanged from Diffy — this class only adds an adaptor that converts a
 * stored proto-binary response into a {@link FieldMap}. The proto message class is configurable
 * <em>per URI</em> via {@link ProtoConfigService}, which loads each response type from its configured
 * compiled jar at runtime (using reflection at initialization). The correct class is selected from
 * the request path of the record being compared.
 */
public class ProtoDynamicAnalyzer extends AbstractDynamicAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ProtoDynamicAnalyzer.class);

    private final ProtoConfigService protoConfigService;

    public ProtoDynamicAnalyzer(ProtoConfigService protoConfigService) {
        if (protoConfigService == null || !protoConfigService.isEnabled()) {
            throw new IllegalArgumentException(
                "ProtoDynamicAnalyzer requires an enabled ProtoConfigService (configure 'proto.config')");
        }
        this.protoConfigService = protoConfigService;
    }

    @Override
    public boolean supports(String contentType) {
        return protoConfigService.isProtoContentType(contentType);
    }

    /**
     * Adaptor: decode a stored proto-binary (base64) response into a {@link FieldMap} using the
     * per-URI proto class resolved from {@code requestPath}. If the payload isn't proto binary or no
     * mapping exists (e.g. an already-lifted JSON payload), fall back to JSON parsing so mixed
     * histories still replay.
     */
    @Override
    public FieldMap decodeResponseFieldMap(String payload, String requestPath) {
        if (payload == null) {
            return decodeJsonFieldMap("{}");
        }
        try {
            byte[] protoBytes = Base64.getDecoder().decode(payload);
            String json = protoConfigService.deserializeToJson(protoBytes, requestPath);
            return decodeJsonFieldMap(json);
        } catch (Exception protoFailure) {
            log.debug("Payload for path {} is not proto binary, falling back to JSON", requestPath);
            return decodeJsonFieldMap(payload);
        }
    }
}
