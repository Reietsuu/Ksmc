package io.ksmc.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Rôle d'un service dans le réseau. */
public enum Environment {
    @JsonProperty("proxy") PROXY,
    @JsonProperty("server") SERVER
}
