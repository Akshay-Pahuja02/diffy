package ai.diffy.repository;

import ai.diffy.analysis.DifferenceResult;
import ai.diffy.transformations.Transformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.util.Date;
import java.util.List;

/**
 * Ensures MongoDB TTL indexes so old records are deleted automatically by the server.
 *
 * <p>Always applies to {@link DifferenceResult}. Optionally applies to {@link Noise} and
 * {@link Transformation} when {@code diffy.retention.include-config=true}.
 */
@Configuration
public class MongoRetentionConfig implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MongoRetentionConfig.class);
    static final String TTL_INDEX_NAME = "ttl_updatedAt";

    @Value("${diffy.retention.days:7}")
    private int retentionDays;

    @Value("${diffy.retention.include-config:false}")
    private boolean includeConfig;

    private final MongoTemplate mongo;

    public MongoRetentionConfig(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (retentionDays <= 0) {
            log.info("Mongo auto-retention disabled (diffy.retention.days={})", retentionDays);
            return;
        }

        Duration ttl = Duration.ofDays(retentionDays);

        backfillDifferenceCreatedAt();
        ensureTtl(DifferenceResult.class, "createdAt", ttl);
        log.info("Mongo TTL active: differenceResult expires after {} day(s)", retentionDays);

        if (includeConfig) {
            backfillUpdatedAt(Noise.class);
            backfillUpdatedAt(Transformation.class);
            ensureTtl(Noise.class, "updatedAt", ttl);
            ensureTtl(Transformation.class, "updatedAt", ttl);
            log.info("Mongo TTL active: noise + transformation expire {} day(s) after last update", retentionDays);
        }
    }

    private void ensureTtl(Class<?> type, String dateField, Duration ttl) {
        IndexOperations ops = mongo.indexOps(type);
        ops.ensureIndex(new Index()
            .on(dateField, Sort.Direction.ASC)
            .expire(ttl)
            .named(TTL_INDEX_NAME));
    }

    private void backfillDifferenceCreatedAt() {
        Query missing = Query.query(Criteria.where("createdAt").exists(false));
        List<DifferenceResult> legacy = mongo.find(missing, DifferenceResult.class);
        if (legacy.isEmpty()) {
            return;
        }

        for (DifferenceResult doc : legacy) {
            Date createdAt = doc.timestampMsec != null ? new Date(doc.timestampMsec) : new Date();
            mongo.updateFirst(
                Query.query(Criteria.where("_id").is(doc.id)),
                Update.update("createdAt", createdAt),
                DifferenceResult.class);
        }
        log.info("Backfilled createdAt on {} legacy differenceResult doc(s)", legacy.size());
    }

    private void backfillUpdatedAt(Class<?> type) {
        Query missing = Query.query(Criteria.where("updatedAt").exists(false));
        long count = mongo.updateMulti(missing, Update.update("updatedAt", new Date()), type).getModifiedCount();
        if (count > 0) {
            log.info("Backfilled updatedAt on {} legacy {} doc(s)", count, type.getSimpleName().toLowerCase());
        }
    }
}
