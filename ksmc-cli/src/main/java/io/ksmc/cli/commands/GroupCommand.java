package io.ksmc.cli.commands;

import io.ksmc.cli.Output;
import picocli.CommandLine.Command;

@Command(name = "group", description = "Groupes de configuration (ServiceGroup)", subcommands = {
        ResourceGroup.ListCmd.class, ResourceGroup.GetCmd.class, ResourceGroup.ApplyCmd.class,
        ResourceGroup.EditCmd.class, ResourceGroup.DeleteCmd.class})
public final class GroupCommand extends ResourceGroup {

    @Override
    protected String path() {
        return "groups";
    }

    @Override
    protected Output.Columns columns() {
        return new Output.Columns().col("NOM", "/metadata/name").col("ENVIRONNEMENTS", "/spec/targetEnvironments")
                .col("TEMPLATES", n -> {
                    StringBuilder sb = new StringBuilder();
                    n.at("/spec/templates").forEach(t -> sb.append(sb.isEmpty() ? "" : ",").append(t.path("name").asText()));
                    return sb.toString();
                }).col("ÂGE", n -> Output.age(n.at("/metadata/creationTimestamp")));
    }
}
