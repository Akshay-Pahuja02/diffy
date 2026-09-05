package ai.diffy.prototest;

import com.flipkart.locusmodels.type.response.EntityResponse;
import com.flipkart.locusmodels.type.response.ServiceabilityResponse;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Mock backends returning shaded ServiceabilityResponse proto bytes.
 */
public class LocusMockServer {

    private static final String PROTO_CT = "application/x-protobuf";

    public static void main(String[] args) {
        if (args.length != 4) {
            System.err.println("Usage: LocusMockServer <primaryPort> <secondaryPort> <candidatePort> <mode>");
            System.err.println("  mode: primary | secondary | candidate");
            System.exit(1);
        }

        String mode = args[3];
        int port = switch (mode) {
            case "primary" -> Integer.parseInt(args[0]);
            case "secondary" -> Integer.parseInt(args[1]);
            case "candidate" -> Integer.parseInt(args[2]);
            default -> throw new IllegalArgumentException("Unknown mode: " + mode);
        };

        DisposableServer server = HttpServer.create()
            .port(port)
            .handle((req, res) -> {
                byte[] body = encode(applyMode(mode));
                return res.status(HttpResponseStatus.OK)
                    .header(HttpHeaderNames.CONTENT_TYPE, PROTO_CT)
                    .send(Mono.just(Unpooled.wrappedBuffer(body)))
                    .then();
            })
            .bindNow();

        System.out.printf("Locus mock %s running on port %d%n", mode, port);
        server.onDispose().block();
    }

    private static ServiceabilityResponse applyMode(String mode) {
        EntityResponse entity = EntityResponse.newBuilder()
            .setRequestId("candidate".equals(mode) ? "req-candidate" : "req-primary")
            .build();

        return ServiceabilityResponse.newBuilder()
            .addResponses(entity)
            .build();
    }

    private static byte[] encode(ServiceabilityResponse response) {
        return response.toByteArray();
    }
}
