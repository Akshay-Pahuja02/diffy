package ai.diffy.lifter;

import ai.diffy.Settings;
import ai.diffy.proxy.HttpMessage;
import ai.diffy.proxy.HttpRequest;
import ai.diffy.proxy.HttpResponse;
import ai.diffy.util.ResourceMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class HttpLifter {

    private static final Logger log = LoggerFactory.getLogger(HttpLifter.class);
    public static final String ControllerEndpointHeaderName = "X-Action-Name";

    public static Exception contentTypeNotSupportedException(String contentType) {
        return new Exception("Content type: " + contentType + " is not supported");
    }

    public static class MalformedJsonContentException extends Exception {
        public MalformedJsonContentException(Throwable cause) {
            super("Malformed Json content");
            initCause(cause);
        }
    }

    private final boolean excludeHttpHeadersComparison;
    private final Optional<ResourceMatcher> resourceMatcher;
    private final ProtoConfigService protoConfigService;

    @Autowired
    public HttpLifter(Settings settings, ProtoConfigService protoConfigService) {
        this.excludeHttpHeadersComparison = settings.excludeHttpHeadersComparison();
        this.resourceMatcher = settings.resourceMatcher();
        this.protoConfigService = protoConfigService;
    }

    private Map<String, Object> headersMap(HttpMessage response) {
        if (!excludeHttpHeadersComparison) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            response.getHeaders().forEach((k, v) -> normalized.put(k.toLowerCase(), v));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("headers", new FieldMap(normalized));
            return m;
        }
        return Map.of();
    }

    public Message liftRequest(HttpRequest req) {
        Map<String, String> headers = req.getHeaders();

        Optional<String> canonicalResource = Optional.ofNullable(headers.get("Canonical-Resource"))
            .or(() -> resourceMatcher.flatMap(rm -> rm.resourceName(req.getPath())))
            .or(() -> Optional.of(req.getMethod() + ":" + req.getPath()));

        Object body = liftRequestBody(req);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method",  req.getMethod());
        m.put("path",    req.getPath());
        m.put("uri",     req.getUri());
        m.put("headers", headers);
        m.put("params",  req.getParams());
        m.put("body",    body);

        return new Message(canonicalResource, new FieldMap(m));
    }

    /**
     * Decode proto request bodies for storage/UI; wire traffic still uses raw bytes in HttpEndpoint.
     */
    private Object liftRequestBody(HttpRequest req) {
        String contentType = contentTypeOfRequest(req);
        boolean protoCandidate = req.getRawBody() != null
            && req.getRawBody().length > 0
            && protoConfigService.isEnabled()
            && protoConfigService.isProtoContentType(contentType)
            && protoConfigService.hasRequestMapping(req.getPath());

        if (protoCandidate) {
            try {
                String json = protoConfigService.deserializeRequestToJson(req.getRawBody(), req.getPath());
                log.info("Proto request deserialization SUCCESS for path: {}", req.getPath());
                return StringLifter.lift(json);
            } catch (Exception e) {
                log.warn("Failed to decode proto request for path {}; falling back to string lifting", req.getPath(), e);
            }
        }
        return StringLifter.lift(req.getBody());
    }

    private static String contentTypeOfRequest(HttpRequest req) {
        Map<String, String> headers = req.getHeaders();
        if (headers == null) return null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if ("content-type".equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public Message liftResponse(LiftResponseInput input) {
        HttpResponse r = input.response();
        String requestPath = input.requestPath();
        Map<String, Object> responseMap = new LinkedHashMap<>();
        responseMap.put("status", r.getStatus());
        responseMap.put("body", liftResponseBody(r, requestPath));
        responseMap.putAll(headersMap(r));
        return new Message(Optional.empty(), new FieldMap(responseMap));
    }

    /**
     * If the response is protobuf and a proto mapping exists for the request path, decode the raw
     * bytes into a JSON string and lift it exactly like any JSON response so the core Diffy
     * comparison runs on JSON. Otherwise (or on any decode failure) fall back to the normal string
     * lifting path.
     */
    private Object liftResponseBody(HttpResponse r, String requestPath) {
        String contentType = contentTypeOf(r);
        
        // Check if response status indicates success (2xx)
        boolean isSuccessStatus = r.getStatus() != null && r.getStatus().startsWith("2");
        
        boolean protoCandidate = r.getRawBody() != null
            && protoConfigService.isEnabled()
            && protoConfigService.isProtoContentType(contentType)
            && protoConfigService.hasMapping(requestPath)
            && isSuccessStatus; // Only attempt proto parsing for successful responses

        if (!isSuccessStatus && protoConfigService.isProtoContentType(contentType)) {
            log.warn("Skipping proto deserialization for non-success response: path={}, status={}", 
                requestPath, r.getStatus());
        }

        if (protoCandidate) {
            try {
                byte[] rawBody = r.getRawBody();
                
                // Log first few bytes in hex for debugging
                StringBuilder hexPreview = new StringBuilder();
                StringBuilder asciiPreview = new StringBuilder();
                int previewLength = Math.min(40, rawBody.length);
                for (int i = 0; i < previewLength; i++) {
                    hexPreview.append(String.format("%02x ", rawBody[i]));
                    // Show ASCII representation if printable
                    char c = (char)(rawBody[i] & 0xFF);
                    asciiPreview.append(c >= 32 && c < 127 ? c : '.');
                }
                
                log.info("Proto deserialization attempt: path={}, status={}, contentType={}, rawBodySize={}, firstBytesHex=[{}], ascii=[{}]", 
                    requestPath, r.getStatus(), contentType, rawBody.length,
                    hexPreview.toString().trim(), asciiPreview.toString());
                
                // Check if response contains error messages like "MALFORMED_REQUEST"
                String bodyString = r.getBody();
                if (bodyString != null && (bodyString.contains("MALFORMED") || bodyString.contains("ERROR") || 
                    bodyString.contains("INVALID") || bodyString.contains("FAILED"))) {
                    log.warn("Proto response appears to be an error message: status={}, body preview: {}", 
                        r.getStatus(), bodyString.length() > 200 ? bodyString.substring(0, 200) : bodyString);
                }
                
                String json = protoConfigService.deserializeToJson(rawBody, requestPath);
                log.info("Proto deserialization SUCCESS for path: {}", requestPath);
                return StringLifter.lift(json);
            } catch (Exception e) {
                log.warn("Failed to decode proto response for path {} (status={}); falling back to string lifting. Body preview: {}",
                    requestPath, r.getStatus(), 
                    r.getBody() != null && r.getBody().length() > 100 ? r.getBody().substring(0, 100) : r.getBody(), e);
            }
        }
        return StringLifter.lift(r.getBody());
    }

    /** Case-insensitive lookup of the Content-Type header. */
    private static String contentTypeOf(HttpResponse r) {
        Map<String, String> headers = r.getHeaders();
        if (headers == null) return null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if ("content-type".equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
