package ai.diffy.analysis;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;

@Document
public class DifferenceResult {
    @Id
    public final String id;
    public String traceId;
    public String endpoint;
    @Indexed
    public Long timestampMsec;
    /** Used by Mongo TTL index ({@link ai.diffy.repository.MongoRetentionConfig}). */
    public Date createdAt;
    public List<FieldDifference> differences;
    public String request;
    public Responses responses;

    public DifferenceResult(String id, String traceId, String endpoint, Long timestampMsec,
            List<FieldDifference> differences, String request, Responses responses, Date createdAt) {
        this.id = id;
        this.traceId = traceId;
        this.endpoint = endpoint;
        this.timestampMsec = timestampMsec;
        this.differences = differences;
        this.request = request;
        this.responses = responses;
        this.createdAt = createdAt;
    }
}

