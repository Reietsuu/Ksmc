package io.ksmc.cli.commands;

import io.ksmc.cli.Output;
import picocli.CommandLine.Command;

@Command(name = "software", description = "Logiciels (SoftwareDefinition)", subcommands = {ResourceGroup.ListCmd.class,
        ResourceGroup.GetCmd.class, ResourceGroup.ApplyCmd.class, ResourceGroup.EditCmd.class,
        ResourceGroup.DeleteCmd.class})
public final class SoftwareCommand extends ResourceGroup {

    @Override
    protected String path() {
        return "software";
    }

    @Override
    protected Output.Columns columns() {
        return new Output.Columns().col("NOM", "/metadata/name").col("ENV", "/spec/environment")
                .col("FAMILLE", "/spec/family").col("RÉSOLVEUR", "/spec/resolver/type").col("DERNIÈRE", "/status/latest");
    }
}
