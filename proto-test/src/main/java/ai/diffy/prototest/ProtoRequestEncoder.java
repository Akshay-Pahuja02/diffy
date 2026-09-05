package ai.diffy.prototest;

import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Path;

/** Encodes a proto UserResponse request body for curl --data-binary @file */
public class ProtoRequestEncoder {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: ProtoRequestEncoder <descPath> <outFile> <name> <age> <email>");
            System.exit(1);
        }
        Descriptor descriptor = loadDescriptor(args[0]);
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(descriptor);
        builder.setField(descriptor.findFieldByName("name"), args[2]);
        builder.setField(descriptor.findFieldByName("age"), Integer.parseInt(args[3]));
        builder.setField(descriptor.findFieldByName("email"), args[4]);
        byte[] bytes = builder.build().toByteArray();
        try (FileOutputStream fos = new FileOutputStream(args[1])) {
            fos.write(bytes);
        }
        System.out.println("Wrote " + bytes.length + " bytes to " + args[1]);
    }

    private static Descriptor loadDescriptor(String path) throws Exception {
        FileDescriptorSet fds;
        try (FileInputStream fis = new FileInputStream(Path.of(path).toAbsolutePath().normalize().toString())) {
            fds = FileDescriptorSet.parseFrom(fis);
        }
        FileDescriptor fd = FileDescriptor.buildFrom(fds.getFile(0), new FileDescriptor[]{});
        return fd.findMessageTypeByName("UserResponse");
    }
}
