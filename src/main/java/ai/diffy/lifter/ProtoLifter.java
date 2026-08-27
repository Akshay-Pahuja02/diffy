package ai.diffy.lifter;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

public class ProtoLifter {
    private static final Logger log = LoggerFactory.getLogger(ProtoLifter.class);

    private final Map<String, Descriptor> descriptorMap;
    private final JsonFormat.Printer jsonPrinter;

    public ProtoLifter(String descriptorPath) throws IOException {
        this.descriptorMap = loadDescriptors(descriptorPath);
        this.jsonPrinter = JsonFormat.printer()
            .preservingProtoFieldNames();
        log.info("ProtoLifter initialized with message types: {}", descriptorMap.keySet());
    }

    private Map<String, Descriptor> loadDescriptors(String path) throws IOException {
        FileDescriptorSet fds;
        try (FileInputStream fis = new FileInputStream(path)) {
            fds = FileDescriptorSet.parseFrom(fis);
        }

        Map<String, FileDescriptorProto> protoFileMap = new LinkedHashMap<>();
        for (FileDescriptorProto fdp : fds.getFileList()) {
            protoFileMap.put(fdp.getName(), fdp);
        }

        Map<String, FileDescriptor> resolvedFiles = new LinkedHashMap<>();
        Map<String, Descriptor> result = new LinkedHashMap<>();

        for (FileDescriptorProto fdp : fds.getFileList()) {
            FileDescriptor fd = resolveFileDescriptor(fdp, protoFileMap, resolvedFiles);
            for (Descriptor md : fd.getMessageTypes()) {
                collectDescriptors(fd.getPackage(), md, result);
            }
        }
        return result;
    }

    private FileDescriptor resolveFileDescriptor(
            FileDescriptorProto fdp,
            Map<String, FileDescriptorProto> protoFileMap,
            Map<String, FileDescriptor> resolved) throws IOException {

        if (resolved.containsKey(fdp.getName())) {
            return resolved.get(fdp.getName());
        }

        List<FileDescriptor> deps = new ArrayList<>();
        for (String dep : fdp.getDependencyList()) {
            FileDescriptorProto depProto = protoFileMap.get(dep);
            if (depProto == null) {
                throw new IOException("Missing dependency: " + dep + " required by " + fdp.getName());
            }
            deps.add(resolveFileDescriptor(depProto, protoFileMap, resolved));
        }

        try {
            FileDescriptor fd = FileDescriptor.buildFrom(fdp, deps.toArray(new FileDescriptor[0]));
            resolved.put(fdp.getName(), fd);
            return fd;
        } catch (DescriptorValidationException e) {
            throw new IOException("Failed to build descriptor for " + fdp.getName(), e);
        }
    }

    private void collectDescriptors(String pkg, Descriptor md, Map<String, Descriptor> result) {
        String fullName = pkg.isEmpty() ? md.getName() : pkg + "." + md.getName();
        result.put(fullName, md);
        for (Descriptor nested : md.getNestedTypes()) {
            collectDescriptors(fullName, nested, result);
        }
    }

    /**
     * Decodes proto binary bytes to a JSON string using the specified message type.
     * Returns a fallback string representation if the type is unknown or decoding fails.
     */
    public String toJson(byte[] protoBytes, String messageType) {
        Descriptor descriptor = descriptorMap.get(messageType);
        if (descriptor == null) {
            log.warn("Unknown proto message type '{}'. Available types: {}", messageType, descriptorMap.keySet());
            return Base64.getEncoder().encodeToString(protoBytes);
        }
        try {
            DynamicMessage message = DynamicMessage.parseFrom(descriptor, protoBytes);
            return jsonPrinter.print(message);
        } catch (Exception e) {
            log.error("Failed to decode proto message of type '{}'", messageType, e);
            return Base64.getEncoder().encodeToString(protoBytes);
        }
    }

    public boolean hasDescriptor(String messageType) {
        return descriptorMap.containsKey(messageType);
    }

    public Set<String> availableTypes() {
        return Collections.unmodifiableSet(descriptorMap.keySet());
    }
}
