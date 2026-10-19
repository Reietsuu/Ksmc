package com.example.hello;

import io.ksmc.plugin.CloudServiceInfo;
import io.ksmc.plugin.Ksmc;
import io.ksmc.plugin.KsmcPluginApi;
import io.ksmc.plugin.Message;
import io.ksmc.plugin.ServiceQuery;
import io.ksmc.plugin.Subscription;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin d'exemple : reçoit les messages du channel {@code hello} (envoyés par le module {@code hello} ou par
 * {@code /cloud say}), les diffuse aux joueurs et publie la propriété {@code example=hello}.
 */
public final class HelloPlugin extends JavaPlugin {

    private Subscription subscription;

    @Override
    public void onEnable() {
        if (!Ksmc.isAvailable()) {
            getLogger().warning("API Ksmc indisponible : ce serveur ne tourne pas dans Ksmc");
            return;
        }
        final KsmcPluginApi api = Ksmc.get();
        getLogger().info("Service Ksmc : " + api.currentService().name());
        api.state().setProperty("example", "hello");

        subscription = api.channels().subscribe("hello", (Message m) -> {
            getLogger().info("[hello] reçu : " + m.message());
            // Le gestionnaire tourne sur un thread de messagerie : repasser sur le thread principal pour Bukkit.
            api.mainThread().execute(() -> Bukkit.broadcastMessage("[Cloud] " + m.message()));
            return m.reply("reçu par " + api.currentService().name());
        });
    }

    @Override
    public void onDisable() {
        if (subscription != null) {
            subscription.close();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!Ksmc.isAvailable()) {
            sender.sendMessage("Ksmc indisponible");
            return true;
        }
        KsmcPluginApi api = Ksmc.get();
        if (args.length >= 2 && args[0].equalsIgnoreCase("say")) {
            String text = sender.getName() + " : " + String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            api.channels().send("environment:server", "hello", text);
            return true;
        }
        for (CloudServiceInfo s : api.services(ServiceQuery.all().running())) {
            sender.sendMessage(s.name() + " [" + s.task() + "] " + s.onlinePlayers() + "/" + s.maxPlayers()
                    + (s.state() == null ? "" : " " + s.state()));
        }
        return true;
    }
}
