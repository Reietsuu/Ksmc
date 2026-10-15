package io.ksmc.api.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Spec qui accepte des réglages libres de modules : {@code spec.extensions.<moduleId>}.
 * Les valeurs sont conservées sans validation.
 */
public interface Extensible {
    Map<String, JsonNode> extensions();
}
