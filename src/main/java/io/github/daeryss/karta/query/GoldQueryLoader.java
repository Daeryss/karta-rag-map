package io.github.daeryss.karta.query;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Path;

/** Reads {@link GoldQuerySet} from a YAML file. */
public final class GoldQueryLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private GoldQueryLoader() {}

    public static GoldQuerySet load(Path yamlPath) throws IOException {
        GoldQuerySet set = YAML.readValue(yamlPath.toFile(), GoldQuerySet.class);
        validate(set);
        return set;
    }

    private static void validate(GoldQuerySet set) {
        if (set.corpusId() == null || set.corpusId().isBlank())
            throw new IllegalArgumentException("GoldQuerySet.corpus_id is required");
        if (set.queries() == null || set.queries().isEmpty())
            throw new IllegalArgumentException("GoldQuerySet.queries must be non-empty");
        for (GoldQuery q : set.queries()) {
            if (q.id() == null || q.id().isBlank())
                throw new IllegalArgumentException("GoldQuery.id is required");
            if (q.query() == null || q.query().isBlank())
                throw new IllegalArgumentException("GoldQuery.id=" + q.id() + ": query text is required");
            if (q.type() == null)
                throw new IllegalArgumentException("GoldQuery.id=" + q.id() + ": type is required");
            if (q.expected() == null || q.expected().isEmpty())
                throw new IllegalArgumentException("GoldQuery.id=" + q.id() + ": expected must be non-empty");
            if (q.hasAdversarial()) {
                if (q.adversarial() == null || q.adversarial().isEmpty())
                    throw new IllegalArgumentException(
                            "GoldQuery.id=" + q.id() + ": has_adversarial=true but no adversarial entries");
                for (GoldQuery.Adversarial a : q.adversarial()) {
                    if (a.file() == null || a.file().isBlank())
                        throw new IllegalArgumentException(
                                "GoldQuery.id=" + q.id() + ": adversarial entry missing 'file'");
                    if (a.type() == null)
                        throw new IllegalArgumentException(
                                "GoldQuery.id=" + q.id() + ": adversarial entry missing 'type'");
                }
            }
        }
    }
}
