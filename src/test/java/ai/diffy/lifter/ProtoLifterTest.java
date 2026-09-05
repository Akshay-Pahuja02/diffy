package ai.diffy.lifter;

import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProtoLifterTest {

    private static ProtoLifter lifter;
    private static Descriptor userResponseDescriptor;

    @BeforeAll
    static void setup() throws Exception {
        Path desc = Path.of("schemas/service.desc").toAbsolutePath().normalize();
        lifter = new ProtoLifter(desc.toString());

        try (FileInputStream fis = new FileInputStream(desc.toFile())) {
            FileDescriptorSet fds = FileDescriptorSet.parseFrom(fis);
            FileDescriptor fd = FileDescriptor.buildFrom(fds.getFile(0), new FileDescriptor[]{});
            userResponseDescriptor = fd.findMessageTypeByName("UserResponse");
        }
    }

    @Test
    void decodesUserResponseToJson() throws Exception {
        DynamicMessage msg = DynamicMessage.newBuilder(userResponseDescriptor)
            .setField(userResponseDescriptor.findFieldByName("name"), "Alice")
            .setField(userResponseDescriptor.findFieldByName("age"), 30)
            .setField(userResponseDescriptor.findFieldByName("email"), "alice@example.com")
            .build();

        String json = lifter.toJson(msg.toByteArray(), "example.UserResponse");

        assertTrue(json.contains("\"name\":\"Alice\"") || json.contains("\"name\": \"Alice\""));
        assertTrue(json.contains("\"age\":30") || json.contains("\"age\": 30"));
        assertTrue(json.contains("alice@example.com"));
    }

    @Test
    void unknownTypeFallsBackToBase64() {
        byte[] bytes = new byte[]{0x0A, 0x05, 0x41, 0x6C, 0x69, 0x63, 0x65};
        String result = lifter.toJson(bytes, "example.UnknownMessage");
        assertDoesNotThrow(() -> java.util.Base64.getDecoder().decode(result));
    }

    @Test
    void hasDescriptorForKnownType() {
        assertTrue(lifter.hasDescriptor("example.UserResponse"));
        assertFalse(lifter.hasDescriptor("example.DoesNotExist"));
    }
}
