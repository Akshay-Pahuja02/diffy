package ai.diffy.proxy;

import ai.diffy.Settings.BaseUrl;
import ai.diffy.Settings.Downstream;
import ai.diffy.Settings.HostPort;
import ai.diffy.functional.endpoints.Endpoint;
import ai.diffy.functional.endpoints.IndependentEndpoint;
import ai.diffy.functional.topology.Async;
import ai.diffy.transformations.TransformationEdge;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServerRequest;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class HttpEndpoint extends IndependentEndpoint<HttpRequest, HttpResponse> {
    private static final Logger log = LoggerFactory.getLogger(HttpEndpoint.class);
    
    private static final Function<HttpServerRequest, CompletableFuture<HttpRequest>> requestBuffer = (req) -> {
        if(req.isMultipart()){
            throw new RuntimeException("Content-Type : multipart/form-data is not supported");
        }
        if(req.isFormUrlencoded() &&
                HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED
                        .contentEquals(req.requestHeaders().get(HttpHeaderNames.CONTENT_TYPE))){
            throw new RuntimeException("Content-Type : application/x-www-form-urlencoded is not supported");
        }
        
        // Check if this is a proto request
        String contentType = req.requestHeaders().get(HttpHeaderNames.CONTENT_TYPE);
        if (isProtoContentType(contentType)) {
            // Read as byte array for proto requests
            return req.receive().aggregate().asByteArray().toFuture().thenApply(body -> new HttpRequest(
                req.method().name(),
                req.uri(),
                req.path(),
                req.params(),
                req.requestHeaders(),
                body,
                TransformationEdge.all.toString()
            ));
        }
        
        // Default: read as string
        return req.receive().aggregate().asString().toFuture().thenApply(body -> new HttpRequest(
            req.method().name(),
            req.uri(),
            req.path(),
            req.params(),
            req.requestHeaders(),
            body,
            TransformationEdge.all.toString()
        ));
    };

    public static final Endpoint<HttpServerRequest, CompletableFuture<HttpRequest>> RequestBuffer =
            Async.contain(Endpoint.from("RequestBuffer", () -> requestBuffer));

    private static final Set<String> PROTO_CONTENT_TYPES = Set.of(
        "application/x-protobuf",
        "application/protobuf",
        "application/grpc",
        "application/grpc+proto"
    );

    private static boolean isProtoContentType(String contentType) {
        if (contentType == null) return false;
        String lower = contentType.toLowerCase();
        return PROTO_CONTENT_TYPES.stream().anyMatch(lower::contains);
    }

    public HttpEndpoint(String name, HttpClient client) {
        super(name, () -> (HttpRequest req) -> {
            log.debug("{} - Sending request: method={}, uri={}, bodySize={}, content-type={}", 
                name, req.getMethod(), req.getUri(), 
                req.getBody() != null ? req.getBody().length() : 0,
                req.getHeaders().get("Content-Type"));
            return client
                .headers(headers -> headers.add(HttpMessage.toHttpHeaders(req.getHeaders())))
                .request(HttpMethod.valueOf(req.getMethod()))
                .uri(req.getUri())
                .send(req.getRawBody() != null && req.getRawBody().length > 0
                    ? ByteBufMono.fromSupplier(() -> Unpooled.wrappedBuffer(req.getRawBody()))
                    : ByteBufMono.fromString(Mono.justOrEmpty(req.getBody())))
                .responseSingle((headers, body) -> {
                    String contentType = headers.responseHeaders().get("content-type");
                    String status = headers.status().toString();
                    
                    if (isProtoContentType(contentType)) {
                        // For proto responses, explicitly aggregate all chunks into a byte array
                        return body.asByteArray()
                            .doOnNext(b -> {
                                log.debug("{} - Proto response: uri={}, status={}, contentType={}, size={}", 
                                    name, req.getUri(), status, contentType, b.length);
                                    
                                // Log details for error responses
                                if (!status.startsWith("2")) {
                                    StringBuilder hex = new StringBuilder();
                                    StringBuilder ascii = new StringBuilder();
                                    int len = Math.min(40, b.length);
                                    for (int i = 0; i < len; i++) {
                                        hex.append(String.format("%02x ", b[i]));
                                        char c = (char)(b[i] & 0xFF);
                                        ascii.append(c >= 32 && c < 127 ? c : '.');
                                    }
                                    log.warn("{} - ERROR response {}: uri={}, errorBytes=[{}], ascii=[{}], reqBody={}", 
                                        name, status, req.getUri(), hex.toString().trim(), ascii.toString(),
                                        req.getBody() != null && req.getBody().length() > 300 
                                            ? req.getBody().substring(0, 300) + "..." 
                                            : req.getBody());
                                }
                            })
                            .map(b -> new HttpResponse(status, headers.responseHeaders(), b));
                    }
                    return body.asString()
                        .map(b -> new HttpResponse(status, headers.responseHeaders(), b));
                }).block();
        });
    }
    public Endpoint<HttpServerRequest, CompletableFuture<HttpResponse>> withSeverRequestBuffer(){
        return Endpoint.from(this.getName(), () -> (serverRequest -> requestBuffer.apply(serverRequest).thenApply(this::apply)));
    }
    private static HttpEndpoint from(String name, String host, int port, int maxHeader) {
        final HttpClient client = HttpClient
                .create().host(host).port(port)
                .httpResponseDecoder(httpResponseDecoderSpec ->
                        httpResponseDecoderSpec
                                .maxHeaderSize(maxHeader));
        return new HttpEndpoint(name, client);
    }
    private static HttpEndpoint from(String name, String baseUrl, int maxHeader) {
        final HttpClient client = HttpClient
                .create().baseUrl(baseUrl)
                .httpResponseDecoder(httpResponseDecoderSpec ->
                        httpResponseDecoderSpec
                                .maxHeaderSize(maxHeader));
        return new HttpEndpoint(name, client);
    }

    public static HttpEndpoint from(String name, Downstream downstream, int maxHeader) {
        if(downstream instanceof BaseUrl){
            return from(name, ((BaseUrl) downstream).baseUrl(), maxHeader);
        }
        HostPort hostport = (HostPort)downstream;
        return from(name, hostport.host(), hostport.port(), maxHeader);
    }
}
