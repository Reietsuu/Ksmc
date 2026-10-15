package io.ksmc.controller.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class StrategicMergeTest {
    final ObjectMapper json = new ObjectMapper();

    ObjectNode obj(String s) throws Exception {
        return (ObjectNode) json.readTree(s.replace('\'', '"'));
    }

    @Test
    void mergesContainersByNameAndAddsSidecars() throws Exception {
        ObjectNode base = obj("{'spec':{'containers':[{'name':'server','image':'a','env':[{'name':'A','value':'1'}]}]}}");
        StrategicMerge.merge(base, obj("""
                {'spec':{'nodeSelector':{'pool':'games'},'containers':[
                  {'name':'server','env':[{'name':'A','value':'2'},{'name':'B','value':'3'}]},
                  {'name':'sidecar','image':'busybox'}]}}"""));

        assertThat(base.at("/spec/nodeSelector/pool").asText()).isEqualTo("games");
        assertThat(base.at("/spec/containers").size()).isEqualTo(2);
        assertThat(base.at("/spec/containers/0/image").asText()).isEqualTo("a");
        assertThat(base.at("/spec/containers/0/env/0/value").asText()).isEqualTo("2");
        assertThat(base.at("/spec/containers/0/env/1/name").asText()).isEqualTo("B");
        assertThat(base.at("/spec/containers/1/name").asText()).isEqualTo("sidecar");
    }

    @Test
    void scalarListsAreReplacedAndNullRemoves() throws Exception {
        ObjectNode base = obj("{'spec':{'containers':[{'name':'server','args':['run']}],'priorityClassName':'low'}}");
        StrategicMerge.merge(base, obj("{'spec':{'containers':[{'name':'server','args':['run','--x']}],'priorityClassName':null}}"));

        assertThat(base.at("/spec/containers/0/args").toString()).isEqualTo("[\"run\",\"--x\"]");
        assertThat(base.at("/spec").has("priorityClassName")).isFalse();
    }
}
