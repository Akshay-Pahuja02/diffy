package ai.diffy.repository;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;

@Document
public class Noise {
    @Id public String endpoint;
    public List<String> noisyfields;
    public Date updatedAt;

    public Noise(String endpoint, List<String> noisyfields) {
        this.endpoint = endpoint;
        this.noisyfields = noisyfields;
        this.updatedAt = new Date();
    }
}
