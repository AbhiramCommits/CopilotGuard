package com.copilotguard.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

@Component
public class PromptTemplateRegistry {

    private final Map<Key, PromptTemplate> templates = new HashMap<>();
    private final Map<String, PromptTemplate> latest = new HashMap<>();

    public PromptTemplateRegistry(ResourceLoader resourceLoader) {
        Resource manifest = resourceLoader.getResource("classpath:prompts/prompts.yaml");
        Object loaded = new Yaml().load(read(manifest));
        if (!(loaded instanceof Map<?, ?> root)
                || !(root.get("templates") instanceof List<?> entries)) {
            throw new IllegalStateException("invalid prompts manifest");
        }
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> e)) {
                continue;
            }
            String id = String.valueOf(e.get("id"));
            String version = String.valueOf(e.get("version"));
            PromptPurpose purpose =
                    PromptPurpose.valueOf(
                            String.valueOf(e.get("purpose"))
                                    .replace('-', '_')
                                    .toUpperCase(Locale.ROOT));
            String file = String.valueOf(e.get("file"));
            String changelog = String.valueOf(e.get("changelog"));
            PromptTemplate template =
                    new PromptTemplate(
                            id,
                            version,
                            purpose,
                            changelog,
                            read(resourceLoader.getResource("classpath:prompts/" + file)));
            templates.put(new Key(id, version), template);
            latest.merge(
                    id,
                    template,
                    (current, candidate) ->
                            compareVersions(candidate.version(), current.version()) >= 0
                                    ? candidate
                                    : current);
        }
    }

    public PromptTemplate get(String id, String version) {
        PromptTemplate template = templates.get(new Key(id, version));
        if (template == null) {
            throw new IllegalStateException("unknown prompt template " + id + ":" + version);
        }
        return template;
    }

    public PromptTemplate latest(String id) {
        PromptTemplate template = latest.get(id);
        if (template == null) {
            throw new IllegalStateException("unknown prompt template id " + id);
        }
        return template;
    }

    private static String read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read prompt resource " + resource, ex);
        }
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = left.replaceFirst("^v", "").split("\\.");
        String[] rightParts = right.replaceFirst("^v", "").split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int l = i < leftParts.length ? Integer.parseInt(leftParts[i]) : 0;
            int r = i < rightParts.length ? Integer.parseInt(rightParts[i]) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private record Key(String id, String version) {}
}
