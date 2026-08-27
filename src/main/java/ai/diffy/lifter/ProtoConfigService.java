package ai.diffy.lifter;

import ai.diffy.Settings;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ProtoConfigService {

    private static final Logger log = LoggerFactory.getLogger(ProtoConfigService.class);
    private static final Set<String> PROTO_CONTENT_TYPES = Set.of(
        "application/x-protobuf"
    );

    private final Map<String, ResolvedMapping> resolvedMappings = new ConcurrentHashMap<>();
    private final JsonFormat.Printer jsonPrinter = JsonFormat.printer().preservingProtoFieldNames();
    private final boolean enabled;

    private record ResolvedMapping(
        Method responseParseFrom,
        Method requestParseFrom,
        Class<?> responseClass,
        Descriptors.Descriptor googleDescriptor
    ) {}

    public ProtoConfigService(Settings settings) {
        Optional<ProtoConfig> protoConfig = settings.protoConfig;
        if (protoConfig.isEmpty() || protoConfig.get().getMappings() == null) {
            this.enabled = false;
            log.info("ProtoConfigService disabled — no protoConfig provided");
            return;
        }

        this.enabled = true;
        ProtoConfig config = protoConfig.get();
        log.info("Loading proto config with {} mappings", config.getMappings().size());

        for (ProtoConfig.URIResponseProto mapping : config.getMappings()) {
            try {
                ResolvedMapping resolved = resolveMapping(mapping);
                resolvedMappings.put(normalizeUri(mapping.getUri()), resolved);
                log.info("Loaded proto mapping: {} -> {}", mapping.getUri(), mapping.getResponseType());
            } catch (Exception e) {
                throw new RuntimeException(
                    "Failed to load proto mapping: " + mapping + ". Ensure the jar exists and contains the specified class.", e);
            }
        }

        log.info("ProtoConfigService initialized with URIs: {}", resolvedMappings.keySet());
    }

    private ResolvedMapping resolveMapping(ProtoConfig.URIResponseProto mapping) throws Exception {
        File jarFile = resolveJarFile(mapping.getJarLocation());

        URLClassLoader classLoader = new URLClassLoader(
            new URL[]{jarFile.toURI().toURL()},
            this.getClass().getClassLoader()
        );

        Class<?> responseClass = classLoader.loadClass(mapping.getResponseType());
        Method responseParseFrom = responseClass.getMethod("parseFrom", byte[].class);

        Method requestParseFrom = null;
        if (mapping.getRequestType() != null && !mapping.getRequestType().isBlank()) {
            try {
                Class<?> requestClass = classLoader.loadClass(mapping.getRequestType());
                requestParseFrom = requestClass.getMethod("parseFrom", byte[].class);
            } catch (Exception e) {
                log.warn(
                    "Could not load requestType {} for URI {} from {} — request proto decode disabled for this mapping",
                    mapping.getRequestType(), mapping.getUri(), jarFile.getName(), e);
            }
        }

        Descriptors.Descriptor googleDescriptor = null;
        if (!Message.class.isAssignableFrom(responseClass)) {
            googleDescriptor = buildGoogleDescriptor(responseClass);
            log.info("Resolved shaded protobuf descriptor for {}", mapping.getResponseType());
        }

        return new ResolvedMapping(responseParseFrom, requestParseFrom, responseClass, googleDescriptor);
    }

    private File resolveJarFile(String location) throws IOException {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("jarLocation is required");
        }

        if (location.startsWith("http://") || location.startsWith("https://")) {
            return downloadJar(location);
        }

        File jarFile = new File(location);
        if (!jarFile.exists()) {
            throw new IllegalArgumentException("Jar not found: " + location);
        }
        return jarFile;
    }

    private File downloadJar(String location) throws IOException {
        String downloadUrl = toArtifactDownloadUrl(location);
        String cacheKey = Integer.toHexString(downloadUrl.hashCode());
        Path cacheDir = Path.of(System.getProperty("user.home"), ".diffy", "proto-jars");
        Files.createDirectories(cacheDir);
        Path cachedJar = cacheDir.resolve(cacheKey + ".jar");

        if (Files.exists(cachedJar) && Files.size(cachedJar) > 0) {
            log.info("Using cached proto jar: {}", cachedJar);
            return cachedJar.toFile();
        }

        log.info("Downloading proto jar from {}", downloadUrl);
        URI uri = URI.create(downloadUrl);
        try (InputStream in = uri.toURL().openStream()) {
            Files.copy(in, cachedJar, StandardCopyOption.REPLACE_EXISTING);
        }

        return cachedJar.toFile();
    }

    /**
     * Converts JFrog UI browse URLs to direct Artifactory download URLs.
     * Example:
     * https://jfrog.../ui/native/maven_internal/com/flipkart/locus-models/1.0.1-jdk-17
     * -> https://jfrog.../artifactory/maven_internal/com/flipkart/locus-models/1.0.1-jdk-17/locus-models-1.0.1-jdk-17.jar
     */
    static String toArtifactDownloadUrl(String location) {
        if (location.contains("/ui/native/")) {
            String artifactoryBase = location.replace("/ui/native/", "/artifactory/");
            if (!artifactoryBase.endsWith(".jar")) {
                String[] parts = artifactoryBase.split("/");
                String version = parts[parts.length - 1];
                String artifactId = parts[parts.length - 2];
                artifactoryBase = artifactoryBase + "/" + artifactId + "-" + version + ".jar";
            }
            return artifactoryBase;
        }

        if (location.endsWith(".jar")) {
            return location;
        }

        throw new IllegalArgumentException(
            "Unsupported jarLocation URL. Provide a direct .jar URL or a JFrog /ui/native/ browse URL.");
    }

    private Descriptors.Descriptor buildGoogleDescriptor(Class<?> messageClass) throws Exception {
        Object messageDescriptor = messageClass.getMethod("getDescriptor").invoke(null);
        Object shadedFile = messageDescriptor.getClass().getMethod("getFile").invoke(messageDescriptor);

        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<byte[]> fileProtos = new ArrayList<>();
        collectShadedFileDescriptors(shadedFile, seen, fileProtos);

        Map<String, DescriptorProtos.FileDescriptorProto> protoFileMap = new LinkedHashMap<>();
        for (byte[] bytes : fileProtos) {
            DescriptorProtos.FileDescriptorProto fdp = DescriptorProtos.FileDescriptorProto.parseFrom(bytes);
            protoFileMap.put(fdp.getName(), fdp);
        }

        Map<String, Descriptors.FileDescriptor> resolvedFiles = new LinkedHashMap<>();
        for (DescriptorProtos.FileDescriptorProto fdp : protoFileMap.values()) {
            resolveGoogleFileDescriptor(fdp, protoFileMap, resolvedFiles);
        }

        Object shadedFileName = shadedFile.getClass().getMethod("getName").invoke(shadedFile);
        Descriptors.FileDescriptor googleFile = resolvedFiles.get(shadedFileName);
        if (googleFile == null) {
            throw new IllegalStateException("Failed to resolve google FileDescriptor for shaded proto");
        }

        String simpleName = messageClass.getSimpleName().replace('$', '.');
        Descriptors.Descriptor descriptor = googleFile.findMessageTypeByName(simpleName);
        if (descriptor == null) {
            descriptor = googleFile.findMessageTypeByName(messageClass.getSimpleName());
        }
        if (descriptor == null) {
            throw new IllegalStateException(
                "Failed to resolve message descriptor for shaded class " + messageClass.getName());
        }
        return descriptor;
    }

    @SuppressWarnings("unchecked")
    private void collectShadedFileDescriptors(Object shadedFile, Set<Object> seen, List<byte[]> out) throws Exception {
        if (!seen.add(shadedFile)) {
            return;
        }

        Object proto = shadedFile.getClass().getMethod("toProto").invoke(shadedFile);
        byte[] bytes = (byte[]) proto.getClass().getMethod("toByteArray").invoke(proto);
        out.add(bytes);

        List<Object> deps = (List<Object>) shadedFile.getClass().getMethod("getDependencies").invoke(shadedFile);
        for (Object dep : deps) {
            collectShadedFileDescriptors(dep, seen, out);
        }
    }

    private Descriptors.FileDescriptor resolveGoogleFileDescriptor(
            DescriptorProtos.FileDescriptorProto fdp,
            Map<String, DescriptorProtos.FileDescriptorProto> protoFileMap,
            Map<String, Descriptors.FileDescriptor> resolved) throws Exception {

        if (resolved.containsKey(fdp.getName())) {
            return resolved.get(fdp.getName());
        }

        List<Descriptors.FileDescriptor> deps = new ArrayList<>();
        for (String dep : fdp.getDependencyList()) {
            DescriptorProtos.FileDescriptorProto depProto = protoFileMap.get(dep);
            if (depProto == null) {
                throw new IllegalStateException("Missing shaded proto dependency: " + dep);
            }
            deps.add(resolveGoogleFileDescriptor(depProto, protoFileMap, resolved));
        }

        Descriptors.FileDescriptor fd = Descriptors.FileDescriptor.buildFrom(
            fdp, deps.toArray(new Descriptors.FileDescriptor[0]));
        resolved.put(fdp.getName(), fd);
        return fd;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isProtoContentType(String contentType) {
        if (contentType == null) return false;
        String lower = contentType.toLowerCase();
        return PROTO_CONTENT_TYPES.stream().anyMatch(lower::contains);
    }

    public boolean hasMapping(String requestPath) {
        return resolveMapping(requestPath) != null;
    }

    /**
     * Exact URI match first, then longest configured prefix (e.g. {@code postal_codes/v4} matches
     * {@code postal_codes/v4/487221/offerings}).
     */
    ResolvedMapping resolveMapping(String requestPath) {
        String normalized = normalizeUri(requestPath);
        ResolvedMapping exact = resolvedMappings.get(normalized);
        if (exact != null) {
            return exact;
        }

        ResolvedMapping best = null;
        int bestPrefixLen = -1;
        for (Map.Entry<String, ResolvedMapping> entry : resolvedMappings.entrySet()) {
            String prefix = entry.getKey();
            if (normalized.equals(prefix) || normalized.startsWith(prefix + "/")) {
                if (prefix.length() > bestPrefixLen) {
                    bestPrefixLen = prefix.length();
                    best = entry.getValue();
                }
            }
        }
        return best;
    }

    public boolean hasRequestMapping(String requestPath) {
        ResolvedMapping mapping = resolveMapping(requestPath);
        return mapping != null && mapping.requestParseFrom() != null;
    }

    /**
     * Deserializes proto bytes to JSON string using the configured response type for the given URI.
     */
    public String deserializeToJson(byte[] protoBytes, String requestPath) {
        ResolvedMapping mapping = resolveMapping(requestPath);
        if (mapping == null) {
            throw new RuntimeException(
                "Proto response received for URI '" + requestPath + "' but no mapping found in protoConfig. " +
                "Available mappings: " + resolvedMappings.keySet());
        }
        return deserializeWith(mapping.responseParseFrom(), mapping, protoBytes, "response", requestPath);
    }

    /** Deserializes request proto bytes using the configured request type for the given URI. */
    public String deserializeRequestToJson(byte[] protoBytes, String requestPath) {
        ResolvedMapping mapping = resolveMapping(requestPath);
        if (mapping == null || mapping.requestParseFrom() == null) {
            throw new RuntimeException(
                "Proto request for URI '" + requestPath + "' but no requestType configured. " +
                "Available mappings: " + resolvedMappings.keySet());
        }
        return deserializeWith(mapping.requestParseFrom(), mapping, protoBytes, "request", requestPath);
    }

    private String deserializeWith(
            Method parseFrom,
            ResolvedMapping mapping,
            byte[] protoBytes,
            String direction,
            String requestPath) {
        try {
            Object parsed = parseFrom.invoke(null, protoBytes);

            if (parsed instanceof Message googleMessage) {
                return jsonPrinter.print(googleMessage);
            }

            if (mapping.googleDescriptor() != null) {
                byte[] wire = (byte[]) parsed.getClass().getMethod("toByteArray").invoke(parsed);
                DynamicMessage dynamicMessage = DynamicMessage.parseFrom(mapping.googleDescriptor(), wire);
                return jsonPrinter.print(dynamicMessage);
            }

            return parsed.toString();
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to deserialize proto " + direction + " for URI '" + requestPath + "'", e);
        }
    }

    private String normalizeUri(String uri) {
        if (uri == null) return "";
        String normalized = uri.split("\\?")[0];
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
