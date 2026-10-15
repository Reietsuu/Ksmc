package io.ksmc.controller.software;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

/** Lecture JSON partagée par les résolveurs. */
final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper();

    static JsonNode parse(String body) throws IOException {
        return MAPPER.readTree(body);
    }

    private Json() {
    }
}
