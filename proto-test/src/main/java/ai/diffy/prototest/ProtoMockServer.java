package ai.diffy.prototest;

import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Three HTTP servers returning JSON or proto UserResponse based on request Content-Type.
 * Primary/secondary echo request data; candidate applies a deterministic regression.
 */
public class ProtoMockServer {

    private static final String MESSAGE_TYPE = "example.UserResponse";
    private static final String DESC_PATH = "schemas/service.desc";
    private static final String PROTO_CT = "application/x-protobuf";
    private static final String JSON_CT = "application/json";

    private record UserData(String name, int age, String email) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("Usage: java -jar proto-test.jar <primaryPort> <secondaryPort> <candidatePort> <mode>");
            System.err.println("  mode: primary | secondary | candidate");
            System.exit(1);
        }

        Descriptor descriptor = loadDescriptor(DESC_PATH);
        String mode = args[3];
        int port = switch (mode) {
            case "primary" -> Integer.parseInt(args[0]);
            case "secondary" -> Integer.parseInt(args[1]);
            case "candidate" -> Integer.parseInt(args[2]);
            default -> throw new IllegalArgumentException("Unknown mode: " + mode);
        };

        DisposableServer server = start(port, descriptor, mode);
        System.out.printf("Mock %s running on port %d (json + proto)%n", mode, port);
        server.onDispose().block();
    }

    private static DisposableServer start(int port, Descriptor descriptor, String mode) {
        return HttpServer.create()
            .port(port)
            .handle((req, res) -> req.receive().aggregate().asByteArray().flatMap(bytes -> {
                String contentType = req.requestHeaders().get(HttpHeaderNames.CONTENT_TYPE);
                boolean protoRequest = contentType != null && contentType.toLowerCase().contains("protobuf");

                UserData input = protoRequest
                    ? decodeProto(descriptor, bytes)
                    : decodeJson(new String(bytes, StandardCharsets.UTF_8));

                UserData output = applyMode(input, mode);
                boolean protoResponse = protoRequest;

                if (protoResponse) {
                    byte[] body = encodeProto(descriptor, output);
                    return res.status(HttpResponseStatus.OK)
                        .header(HttpHeaderNames.CONTENT_TYPE, PROTO_CT)
                        .header("X-Proto-Message", MESSAGE_TYPE)
                        .send(Mono.just(Unpooled.wrappedBuffer(body)))
                        .then();
                }

                String body = encodeJson(output);
                return res.status(HttpResponseStatus.OK)
                    .header(HttpHeaderNames.CONTENT_TYPE, JSON_CT)
                    .send(Mono.just(Unpooled.wrappedBuffer(body.getBytes(StandardCharsets.UTF_8))))
                    .then();
            }))
            .bindNow();
    }

    private static UserData applyMode(UserData input, String mode) {
        if ("candidate".equals(mode)) {
            return new UserData(input.name(), input.age() + 1, input.email().replace("@", "@new."));
        }
        return input;
    }

    private static UserData decodeJson(String body) {
        if (body == null || body.isBlank()) {
            return new UserData("Unknown", 0, "unknown@example.com");
        }
        return new UserData(
            extractJsonString(body, "name"),
            extractJsonInt(body, "age"),
            extractJsonString(body, "email")
        );
    }

    private static UserData decodeProto(Descriptor descriptor, byte[] bytes) {
        try {
            DynamicMessage msg = DynamicMessage.parseFrom(descriptor, bytes);
            return new UserData(
                (String) msg.getField(descriptor.findFieldByName("name")),
                (Integer) msg.getField(descriptor.findFieldByName("age")),
                (String) msg.getField(descriptor.findFieldByName("email"))
            );
        } catch (Exception e) {
            return new UserData("Unknown", 0, "unknown@example.com");
        }
    }

    private static byte[] encodeProto(Descriptor descriptor, UserData data) {
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(descriptor);
        builder.setField(descriptor.findFieldByName("name"), data.name());
        builder.setField(descriptor.findFieldByName("age"), data.age());
        builder.setField(descriptor.findFieldByName("email"), data.email());
        return builder.build().toByteArray();
    }

    private static String encodeJson(UserData data) {
        return "{\"name\":\"" + escape(data.name()) + "\",\"age\":" + data.age()
            + ",\"email\":\"" + escape(data.email()) + "\"}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String extractJsonString(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static int extractJsonInt(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static Descriptor loadDescriptor(String path) throws Exception {
        FileDescriptorSet fds;
        try (FileInputStream fis = new FileInputStream(path)) {
            fds = FileDescriptorSet.parseFrom(fis);
        }
        FileDescriptor fd = FileDescriptor.buildFrom(fds.getFile(0), new FileDescriptor[]{});
        Descriptor descriptor = fd.findMessageTypeByName("UserResponse");
        if (descriptor == null) {
            throw new IllegalStateException("UserResponse not found in " + path);
        }
        return descriptor;
    }
}
