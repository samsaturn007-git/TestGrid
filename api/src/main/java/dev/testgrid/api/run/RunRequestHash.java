package dev.testgrid.api.run;

import dev.testgrid.api.common.CanonicalJson;
import dev.testgrid.api.common.Hashing;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Contracts §4 request hash: sha256 of the canonical JSON of the request, with {@code
 * idempotencyKey} excluded, targets sorted, null fields dropped and the sharding default filled in.
 * Two requests that mean the same run hash the same, however the client ordered or omitted them.
 */
public final class RunRequestHash {

  private RunRequestHash() {}

  public static byte[] of(CreateRunRequest request, JsonMapper mapper) {
    return Hashing.sha256(canonicalJson(request, mapper));
  }

  static String canonicalJson(CreateRunRequest request, JsonMapper mapper) {
    CreateRunRequest normalized =
        new CreateRunRequest(
            request.suiteKey(),
            request.targets(),
            request.trigger(),
            null,
            request.fixture(),
            request.shardingOrDefault(),
            request.source());
    JsonNode tree = withoutNulls(mapper.valueToTree(normalized));
    ArrayNode targets = (ArrayNode) tree.get("targets");
    List<JsonNode> sorted = new ArrayList<>();
    targets.forEach(sorted::add);
    sorted.sort(Comparator.comparing(CanonicalJson::write));
    targets.removeAll();
    sorted.forEach(targets::add);
    return CanonicalJson.write(tree);
  }

  private static JsonNode withoutNulls(JsonNode node) {
    if (node instanceof ObjectNode object) {
      ObjectNode copy = object.objectNode();
      object
          .properties()
          .forEach(e -> {
            if (!e.getValue().isNull()) {
              copy.set(e.getKey(), withoutNulls(e.getValue()));
            }
          });
      return copy;
    }
    if (node instanceof ArrayNode array) {
      ArrayNode copy = array.arrayNode();
      array.forEach(item -> copy.add(withoutNulls(item)));
      return copy;
    }
    return node;
  }
}
