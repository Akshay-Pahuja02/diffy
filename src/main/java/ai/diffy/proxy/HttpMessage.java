package ai.diffy.proxy;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;

public abstract class HttpMessage {
    private static final Logger log = LoggerFactory.getLogger(HttpMessage.class);
    Map<String, String> headers;
    String body;
    // Raw bytes as received on the wire. Kept alongside the String body so binary payloads
    // (e.g. protobuf responses) survive without lossy UTF-8 round-tripping.
    // JsonIgnore prevents Jackson from trying to serialize this as a JSON array during any
    // intermediate serialization (transformations, caching, etc.)
    @JsonIgnore
    byte[] rawBody;

    public HttpMessage(){}
    public HttpMessage(HttpHeaders headers, String body) {
        this.headers = group(headers.entries());
        this.body = body;
        this.rawBody = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    }
    public HttpMessage(HttpHeaders headers, byte[] rawBody) {
        this.headers = group(headers.entries());
        this.rawBody = rawBody == null ? new byte[0] : rawBody;
        this.body = new String(this.rawBody, StandardCharsets.UTF_8);
        
        // Log when binary data might be getting corrupted
        if (this.rawBody.length > 0) {
            // Check if the body string conversion is lossy (binary data contains invalid UTF-8)
            byte[] reconverted = this.body.getBytes(StandardCharsets.UTF_8);
            if (!Arrays.equals(this.rawBody, reconverted)) {
                log.debug("HttpMessage: rawBody->UTF8->bytes round-trip is lossy. " +
                    "Original size: {}, reconverted size: {}. This is expected for binary proto data.",
                    this.rawBody.length, reconverted.length);
            }
        }
    }

    public Map<String, String> getHeaders(){
        return this.headers;
    }
    private static Map<String, String> group(Iterable<Map.Entry<String, String>> entries){
        Map<String, List<String>> grouped = new TreeMap<>();
        entries.forEach(entry -> {
            grouped.putIfAbsent(entry.getKey(), new ArrayList<>());
            grouped.get(entry.getKey()).add(entry.getValue());
        });
        Map<String, String> values = new HashMap<>(grouped.size());
        grouped.entrySet().forEach(entry -> {
            values.put(entry.getKey(), String.join(",",entry.getValue().stream().sorted().toList()));
        });
        return values;
    }
    public String getBody(){
        return body;
    }

    public byte[] getRawBody(){
        return rawBody;
    }

    public static HttpHeaders toHttpHeaders(Map<String, String> entries) {
        HttpHeaders result = EmptyHttpHeaders.INSTANCE.copy();
        // Values are stored as a single string per header (see group()); splitting on "," here
        // would corrupt any header whose value legitimately contains a comma (e.g. a JSON blob).
        entries.forEach(result::add);
        return result;
    }
    @Override
    public String toString() {
        String headers = this.getHeaders().entrySet().stream().map(entry -> entry.getKey() + " : " + entry.getValue()).reduce((e1, e2) -> e1 + "\n" + e2).orElse("");
        return "\n" + headers + "\n" + body;
    }
}
