package ai.diffy.prototest;

import com.flipkart.locusmodels.type.response.EntityResponse;
import com.flipkart.locusmodels.type.response.ServiceabilityResponse;

/**
 * Quick check that locus-models proto bytes decode to JSON via the same path Diffy uses.
 */
public class LocusProtoDecodeCheck {

    public static void main(String[] args) throws Exception {
        ServiceabilityResponse response = ServiceabilityResponse.newBuilder()
            .addResponses(EntityResponse.newBuilder().setRequestId("req-test").build())
            .build();

        byte[] bytes = response.toByteArray();
        System.out.println("Proto bytes length: " + bytes.length);

        // Mirror ProtoConfigService shaded path: parseFrom + toByteArray + google DynamicMessage
        var parseFrom = com.flipkart.locusmodels.type.response.ServiceabilityResponse.class
            .getMethod("parseFrom", byte[].class);
        Object parsed = parseFrom.invoke(null, bytes);
        System.out.println("Parsed class: " + parsed.getClass().getName());
        System.out.println("Parsed toString: " + parsed);
    }
}
