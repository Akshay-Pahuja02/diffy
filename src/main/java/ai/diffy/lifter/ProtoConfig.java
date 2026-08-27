package ai.diffy.lifter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ProtoConfig {

    private List<URIResponseProto> mappings;

    public ProtoConfig() {}

    public List<URIResponseProto> getMappings() {
        return mappings;
    }

    public void setMappings(List<URIResponseProto> mappings) {
        this.mappings = mappings;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class URIResponseProto {
        private String uri;
        private String jarLocation;
        private String responseType;
        private String requestType;

        public URIResponseProto() {}

        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }

        public String getJarLocation() { return jarLocation; }
        public void setJarLocation(String jarLocation) { this.jarLocation = jarLocation; }

        public String getResponseType() { return responseType; }
        public void setResponseType(String responseType) { this.responseType = responseType; }

        public String getRequestType() { return requestType; }
        public void setRequestType(String requestType) { this.requestType = requestType; }

        @Override
        public String toString() {
            return "URIResponseProto{uri='" + uri + "', jarLocation='" + jarLocation + "', responseType='" + responseType + "'}";
        }
    }

    public static ProtoConfig loadFromFile(String path) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(new File(path), ProtoConfig.class);
    }
}
