package dev.testgrid.api.config;

import dev.testgrid.api.common.ApiNullable;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the generated spec describe what the API really sends, so the TypeScript types are exact
 * rather than "everything optional".
 */
@Configuration(proxyBeanMethods = false)
@SuppressWarnings({"rawtypes", "unchecked"}) // swagger-models exposes raw Schema types
class OpenApiShape {

  private static final String REF_PREFIX = "#/components/schemas/";

  /** Response properties that are left out (not null) when empty. */
  private static final Map<String, Set<String>> OMITTED_WHEN_EMPTY =
      Map.of("Problem", Set.of("errors", "type"));

  /** {@link ApiNullable} fields become {@code T | null} (OpenAPI 3.1 type arrays / oneOf). */
  @Bean
  PropertyCustomizer apiNullableProperties() {
    return (property, type) -> {
      if (type.getCtxAnnotations() == null
          || Arrays.stream(type.getCtxAnnotations()).noneMatch(a -> a instanceof ApiNullable)) {
        return property;
      }
      if (property.get$ref() != null) {
        Schema<Object> nullable = new JsonSchema();
        nullable.addOneOfItem(new JsonSchema().$ref(property.get$ref()));
        Schema<Object> nullType = new JsonSchema();
        nullType.setTypes(new TreeSet<>(Set.of("null")));
        nullable.addOneOfItem(nullType);
        return nullable;
      }
      property.addType("null");
      return property;
    };
  }

  /**
   * Jackson writes every response field, null or not, so for schemas used only in responses every
   * property is required. Schemas also reachable from a request body keep their request rules.
   */
  @Bean
  OpenApiCustomizer responseFieldsAreRequired() {
    return openApi -> {
      Map<String, Schema> schemas = openApi.getComponents().getSchemas();
      Set<String> fromRequests = new HashSet<>();
      Set<String> fromResponses = new HashSet<>();
      for (Operation op : operations(openApi)) {
        if (op.getRequestBody() != null) {
          collect(op.getRequestBody().getContent(), schemas, fromRequests);
        }
        op.getResponses().values().forEach(r -> collect(r.getContent(), schemas, fromResponses));
      }
      fromResponses.removeAll(fromRequests);
      for (String name : fromResponses) {
        Schema schema = schemas.get(name);
        if (schema.getProperties() != null) {
          Set<String> required = new TreeSet<>(schema.getProperties().keySet());
          required.removeAll(OMITTED_WHEN_EMPTY.getOrDefault(name, Set.of()));
          schema.setRequired(new ArrayList<>(required));
        }
      }
    };
  }

  private static List<Operation> operations(OpenAPI openApi) {
    List<Operation> ops = new ArrayList<>();
    openApi.getPaths().values().forEach(p -> ops.addAll(p.readOperations()));
    return ops;
  }

  private static void collect(Content content, Map<String, Schema> schemas, Set<String> out) {
    if (content == null) {
      return;
    }
    Deque<Schema> todo = new ArrayDeque<>();
    content.values().forEach(m -> todo.add(m.getSchema()));
    while (!todo.isEmpty()) {
      Schema s = todo.pop();
      if (s == null) {
        continue;
      }
      if (s.get$ref() != null) {
        String name = s.get$ref().substring(REF_PREFIX.length());
        if (out.add(name) && schemas.get(name) != null) {
          todo.add(schemas.get(name));
        }
        continue;
      }
      if (s.getProperties() != null) {
        todo.addAll(s.getProperties().values());
      }
      if (s.getItems() != null) {
        todo.add(s.getItems());
      }
      if (s.getOneOf() != null) {
        todo.addAll(s.getOneOf());
      }
    }
  }
}
