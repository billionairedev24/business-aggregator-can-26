package ca.northline.merchants.integration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * An in-memory namespace for the reconciler tests: list by one {@code key=value} label, apply = replace (the
 * reconciler is its objects' only field manager), delete. Status is set by the test, the way cert-manager and the
 * Gateway controller would, and survives an apply (the API server keeps status on a spec apply).
 */
final class FakeKubeApi implements KubeApi {

    final Map<Kind, Map<String, ObjectNode>> objects = new LinkedHashMap<>();
    final List<String> calls = new ArrayList<>();

    @Override
    public List<JsonNode> list(Kind kind, String labelSelector) {
        var parts = labelSelector.split("=", 2);
        return objects.getOrDefault(kind, Map.of()).values().stream()
                .filter(o -> parts[1].equals(
                        o.path("metadata").path("labels").path(parts[0]).asString()))
                .map(o -> (JsonNode) o.deepCopy())
                .toList();
    }

    @Override
    public void apply(Kind kind, ObjectNode object) {
        var name = object.path("metadata").path("name").asString();
        calls.add("apply " + kind.kind + " " + name);
        var stored = object.deepCopy();
        var previous = get(kind, name);
        if (previous != null && previous.has("status")) {
            stored.set("status", previous.get("status"));
        }
        objects.computeIfAbsent(kind, _ -> new LinkedHashMap<>()).put(name, stored);
    }

    @Override
    public void delete(Kind kind, String name) {
        calls.add("delete " + kind.kind + " " + name);
        objects.getOrDefault(kind, new LinkedHashMap<>()).remove(name);
    }

    ObjectNode get(Kind kind, String name) {
        return objects.getOrDefault(kind, Map.of()).get(name);
    }

    List<ObjectNode> all(Kind kind) {
        return List.copyOf(objects.getOrDefault(kind, Map.of()).values());
    }
}
