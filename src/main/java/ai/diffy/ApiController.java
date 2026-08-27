package ai.diffy;

import ai.diffy.analysis.*;
import ai.diffy.compare.Difference;
import ai.diffy.lifter.JsonLifter;
import ai.diffy.lifter.ProtoConfigService;
import ai.diffy.repository.DifferenceResultRepository;
import ai.diffy.repository.NoiseRepository;
import ai.diffy.transformations.TransformationCachingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@RestController
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final NoiseRepository noise;
    private final DifferenceResultRepository repository;
    private final TransformationCachingService transformations;
    private final Settings settings;
    private final DynamicAnalyzer jsonDynamicAnalyzer;
    private final DynamicAnalyzer protoDynamicAnalyzer;

    private final Map<String, String> MissingEndpointException;
    private final Map<String, String> MissingEndpointPathException;
    private final Map<String, String> RequestPurgedException;
    private final Map<String, String> IndexOutOfBoundsException;
    private final Predicate<JoinedField> thresholdFilter;

    @Autowired
    public ApiController(
            NoiseRepository noise,
            DifferenceResultRepository repository,
            TransformationCachingService transformations,
            Settings settings,
            ProtoConfigService protoConfigService) {
        this.noise          = noise;
        this.repository     = repository;
        this.transformations = transformations;
        this.settings       = settings;
        this.jsonDynamicAnalyzer = new JSONDynamicAnalyzer();

        DynamicAnalyzer proto = null;
        if (protoConfigService != null && protoConfigService.isEnabled()) {
            try {
                proto = new ProtoDynamicAnalyzer(protoConfigService);
            } catch (Exception e) {
                log.error("Failed to initialize ProtoDynamicAnalyzer; "
                    + "proto requests will fall back to JSON analysis", e);
            }
        }
        this.protoDynamicAnalyzer = proto;

        MissingEndpointException     = Renderer.error("Specify an endpoint");
        MissingEndpointPathException = Renderer.error("Specify an endpoint and path");
        RequestPurgedException       = Renderer.error("Request purged");
        IndexOutOfBoundsException    = Renderer.error("Request index out of bounds");
        thresholdFilter = DifferencesFilterFactory.apply(
            settings.relativeThreshold,
            settings.absoluteThreshold
        );
    }

    /**
     * Selects the {@link DynamicAnalyzer} implementation for a stored record based on the content
     * type declared by its request. Proto APIs ({@code application/x-protobuf}) use
     * {@link ProtoDynamicAnalyzer} when configured; everything else falls back to the JSON analyzer.
     */
    private DynamicAnalyzer selectAnalyzer(String contentType) {
        if (protoDynamicAnalyzer != null && protoDynamicAnalyzer.supports(contentType)) {
            return protoDynamicAnalyzer;
        }
        return jsonDynamicAnalyzer;
    }

    private Report proxy(long start, long end) {
        return AbstractDynamicAnalyzer.replay(repository, start, end, this::selectAnalyzer, settings);
    }

    private Map<String, JoinedField> visibleFields(
            JoinedEndpoint joinedEndpoint,
            String endpoint,
            boolean excludeNoise) {
        List<String> noisyFields = excludeNoise
            ? noise.findById(endpoint).map(n -> n.noisyfields).orElse(List.of())
            : List.of();

        return joinedEndpoint.fields().entrySet().stream()
            .filter(e -> !e.getKey().startsWith("request."))
            .filter(e -> e.getValue().raw().differences() > 0)
            .filter(e -> {
                if (!excludeNoise) return true;
                JoinedField field = e.getValue();
                return thresholdFilter.test(field)
                    && noisyFields.stream().noneMatch(prefix -> e.getKey().startsWith(prefix));
            })
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                (a, b) -> a, LinkedHashMap::new));
    }

    private Map<String, Object> endpointMap(
            String ep,
            JoinedEndpoint joinedEndpoint,
            boolean includeWeights,
            boolean excludeNoise) {
        Map<String, JoinedField> fieldsMap = visibleFields(joinedEndpoint, ep, excludeNoise);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("endpoint", Renderer.endpoint(joinedEndpoint.endpoint()));
        m.put("fields",   Renderer.fields(fieldsMap, includeWeights));
        return m;
    }

    @GetMapping(path = "/api/1/overview")
    public Map<String, Object> getOverview(
            @RequestParam(name = "exclude_noise",    defaultValue = "true")  boolean excludeNoise,
            @RequestParam(name = "include_weights",  defaultValue = "false") boolean includeWeights,
            @RequestParam(name = "start",            defaultValue = "0")     long start,
            @RequestParam(name = "end",              defaultValue = "1701001001000") long end) {
        Map<String, Object> result = new LinkedHashMap<>();
        proxy(start, end).joinedDifferences().endpoints()
            .forEach((endpoint, diffs) ->
                result.put(endpoint, endpointMap(endpoint, diffs, includeWeights, excludeNoise)));
        return result;
    }

    @GetMapping(path = "/api/1/endpoints")
    public Map<String, Object> getEndpoints(
            @RequestParam(name = "exclude_noise", defaultValue = "false") boolean excludeNoise,
            @RequestParam(name = "start",         defaultValue = "0")     long start,
            @RequestParam(name = "end",           defaultValue = "1701001001000") long end) {
        Map<String, EndpointMetadata> eps = proxy(start, end).joinedDifferences().raw().counter().endpoints();
        Map<String, EndpointMetadata> filtered = eps.entrySet().stream()
            .filter(e -> {
                if (!excludeNoise) return true;
                Object fields = getStats(e.getKey(), excludeNoise, false, start, end).get("fields");
                if (fields instanceof Map<?, ?> fm) return !fm.isEmpty();
                return true;
            })
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                (a, b) -> a, LinkedHashMap::new));
        return new LinkedHashMap<>(Renderer.endpoints(filtered));
    }

    @GetMapping(path = "/api/1/endpoints/{endpoint}/stats")
    public Map<String, Object> getStats(
            @PathVariable(name = "endpoint")                              String endpoint,
            @RequestParam(name = "exclude_noise",   defaultValue = "true")  boolean excludeNoise,
            @RequestParam(name = "include_weights", defaultValue = "false") boolean includeWeights,
            @RequestParam(name = "start",           defaultValue = "0")     long start,
            @RequestParam(name = "end",             defaultValue = "1701001001000") long end) {
        if (endpoint.isEmpty()) return new LinkedHashMap<>(MissingEndpointException);
        try {
            JoinedEndpoint joined = proxy(start, end).joinedDifferences().endpoint(endpoint);
            return endpointMap(endpoint, joined, includeWeights, excludeNoise);
        } catch (Exception t) {
            return new LinkedHashMap<>(Renderer.error(t.getMessage()));
        }
    }

    @GetMapping(path = "/api/1/endpoints/{endpoint}/fields/{path}/results")
    public Map<String, Object> getResults(
            @PathVariable("endpoint")                                      String endpoint,
            @PathVariable("path")                                          String path,
            @RequestParam(name = "include_request", defaultValue = "false") boolean includeRequest,
            @RequestParam(name = "exclude_noise",   defaultValue = "true")  boolean excludeNoise,
            @RequestParam(name = "start",           defaultValue = "0")     long start,
            @RequestParam(name = "end",             defaultValue = "1701001001000") long end) {
        if (endpoint.isEmpty() || path.isEmpty()) {
            return new LinkedHashMap<>(MissingEndpointPathException);
        }
        Report report = proxy(start, end);
        JoinedEndpoint joined = report.joinedDifferences().endpoint(endpoint);
        Map<String, JoinedField> joinedFields = visibleFields(joined, endpoint, excludeNoise);
        List<String> noisyPrefixes = excludeNoise
            ? noise.findById(endpoint).map(n -> n.noisyfields).orElse(List.of())
            : List.of();

        List<DifferenceResult> drs = report.collector().prefix(new Field(endpoint, path));

        List<Map<String, Object>> requests = new ArrayList<>();
        for (DifferenceResult dr : drs) {
            Map<String, Difference> noiseByPath = excludeNoise
                ? NoiseFilter.decodeNoiseFromStored(dr, settings.listComparisonMode())
                : null;

            Map<String, Object> rawDiffs = Renderer.differences(dr.differences);
            Map<String, Object> filtered = excludeNoise
                ? NoiseFilter.filterDifferenceMap(rawDiffs, noiseByPath, joinedFields, thresholdFilter, noisyPrefixes)
                : rawDiffs;

            if (filtered.isEmpty()) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", dr.id);
            row.put("differences", filtered);
            if (includeRequest) {
                row.put("request", JsonLifter.decode(dr.request));
            }
            requests.add(row);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("endpoint", endpoint);
        m.put("path",     path);
        m.put("requests", requests);
        return m;
    }

    @GetMapping(path = "/api/1/endpoints/{endpoint}/fields/{path}/results/{index}")
    public Map<String, Object> getIndex(
            @PathVariable("endpoint")                                      String endpoint,
            @PathVariable("path")                                          String path,
            @PathVariable("index")                                         int index,
            @RequestParam(name = "include_request", defaultValue = "true") boolean includeRequest,
            @RequestParam(name = "start",           defaultValue = "0")    long start,
            @RequestParam(name = "end",             defaultValue = "1701001001000") long end) {
        if (endpoint.isEmpty() || path.isEmpty()) {
            return new LinkedHashMap<>(IndexOutOfBoundsException);
        }
        List<DifferenceResult> drs = proxy(start, end).collector()
            .prefix(new Field(endpoint, path));
        if (index >= 0 && index < drs.size()) {
            return Renderer.differenceResult(drs.get(index), includeRequest);
        }
        return new LinkedHashMap<>(IndexOutOfBoundsException);
    }

    @GetMapping(path = "/api/1/requests/{id}")
    public Map<String, Object> getRequest(
            @PathVariable("id")                                            String id,
            @RequestParam(name = "include_request", defaultValue = "true") boolean includeRequest,
            @RequestParam(name = "start",           defaultValue = "0")    long start,
            @RequestParam(name = "end",             defaultValue = "1701001001000") long end) {
        if (id.isEmpty()) return new LinkedHashMap<>(RequestPurgedException);
        return repository.findById(id)
            .map(dr -> Renderer.differenceResult(dr, includeRequest))
            .orElse(new LinkedHashMap<>(RequestPurgedException));
    }

    @GetMapping(path = "/api/1/clear")
    public Map<String, String> clear() {
        repository.deleteAll();
        noise.deleteAll();
        transformations.clearAll();
        return Renderer.success("Diffs, noise rules, and transformations cleared");
    }

    @GetMapping(path = "/api/1/info")
    public Map<String, Object> getInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name",               settings.serviceName);
        m.put("candidate",          httpServiceToMap(settings.candidate.toString()));
        m.put("primary",            httpServiceToMap(settings.primary.toString()));
        m.put("secondary",          httpServiceToMap(settings.secondary.toString()));
        m.put("relativeThreshold",  settings.relativeThreshold);
        m.put("absoluteThreshold",  settings.absoluteThreshold);
        m.put("listComparisonMode", settings.listComparisonMode().name());
        m.put("protocol",           "http");
        return m;
    }

    @PutMapping(path = "/api/1/settings/listComparisonMode")
    public Map<String, String> setListComparisonMode(@RequestBody Map<String, String> body) {
        String mode = body.getOrDefault("mode", "LEGACY").trim().toUpperCase();
        settings.setListComparisonMode(ai.diffy.compare.ListComparisonMode.valueOf(mode));
        return Map.of("listComparisonMode", settings.listComparisonMode().name());
    }

    private Map<String, String> httpServiceToMap(String target) {
        return Map.of("target", target);
    }
}
