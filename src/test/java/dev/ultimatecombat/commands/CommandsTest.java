package dev.ultimatecombat.commands;

import dev.ultimatecombat.TestBase;
import dev.ultimatecombat.core.command.UCCommand;
import dev.ultimatecombat.core.message.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenario 30: every command and permission. Each command path is executed (and tab completed)
 * by the console, an operator and a normal player without errors; admin commands refuse normal
 * players; every permission checked by a command is declared in plugin.yml.
 */
class CommandsTest extends TestBase {

    private final List<List<String>> paths = new ArrayList<>();
    private final List<UCCommand> commands = new ArrayList<>();

    private void collect(UCCommand command, List<String> path) {
        paths.add(path);
        commands.add(command);
        for (UCCommand child : command.children()) {
            List<String> sub = new ArrayList<>(path);
            sub.add(child.name());
            collect(child, sub);
        }
    }

    @Override
    protected void configure() {
        // switch every module on so that no command is answered with "module disabled"
        for (var module : plugin.modules().all()) {
            set("features.yml", "modules." + module.id(), true);
        }
        plugin.reload("all");
        collect(plugin.commands().root(), List.of());
    }

    private void run(CommandSender sender, List<String> path) {
        Command command = server.getCommandMap().getCommand("ultimatecombat");
        assertNotNull(command);
        String[] args = path.toArray(new String[0]);
        try {
            command.execute(sender, "ultimatecombat", args);
            List<String> tab = new ArrayList<>(path);
            tab.add("");
            command.tabComplete(sender, "ultimatecombat", tab.toArray(new String[0]));
        } catch (RuntimeException e) {
            throw new AssertionError("/uc " + String.join(" ", path) + " failed for " + sender.getName(), e);
        }
    }

    @Test
    void everyCommandRunsForConsoleOperatorAndPlayer() throws Exception {
        PlayerMock op = join("Operator");
        op.setOp(true);
        PlayerMock player = join("Player");
        assertTrue(paths.size() > 40, "command tree collected: " + paths.size());
        for (List<String> path : paths) {
            run(server.getConsoleSender(), path);
            run(op, path);
            run(player, path);
            settle();
        }
        assertTrue(plugin.isEnabled());
    }

    @Test
    void adminCommandsRefuseNormalPlayers() throws Exception {
        PlayerMock player = join("Player");
        String denied = Text.strip(plugin.messages().text(player, "general.no-permission"));
        for (String admin : List.of("reload", "validate", "backup", "migrate", "debug", "status", "editor", "resetstats", "exportstats",
                "abuse", "customtools", "leaderboard", "tag", "untag")) {
            while (player.nextMessage() != null) {
                // drain
            }
            server.getCommandMap().getCommand("ultimatecombat").execute(player, "ultimatecombat", new String[]{admin});
            String reply = player.nextMessage();
            assertNotNull(reply, "/uc " + admin + " answered");
            assertTrue(Text.strip(reply).contains(denied), "/uc " + admin + " refused: " + reply);
        }
    }

    @Test
    void everyCommandPermissionIsDeclaredWithTheRightDefault() throws Exception {
        YamlConfiguration pluginYml = new YamlConfiguration();
        try (var in = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            pluginYml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        for (UCCommand command : commands) {
            String perm = command.permission();
            if (perm == null || perm.isEmpty()) {
                continue;
            }
            assertTrue(pluginYml.isConfigurationSection("permissions." + perm), "permission declared: " + perm);
            Permission registered = server.getPluginManager().getPermission(perm);
            assertNotNull(registered, "permission registered: " + perm);
            String def = pluginYml.getString("permissions." + perm + ".default");
            assertEquals(PermissionDefault.getByName(def), registered.getDefault(), perm);
        }
    }

    @Test
    void bypassPermissionsAreNeverGrantedByDefault() throws Exception {
        PlayerMock op = join("Operator");
        op.setOp(true);
        for (Permission p : server.getPluginManager().getPermissions()) {
            if (p.getName().startsWith("ultimatecombat.bypass")) {
                assertEquals(PermissionDefault.FALSE, p.getDefault(), p.getName());
                assertTrue(!op.hasPermission(p), "operators do not get " + p.getName());
            }
        }
        assertTrue(op.hasPermission("ultimatecombat.admin"));
        assertTrue(!op.hasPermission("ultimatecombat.bypass.combat-tag"));
    }

    @Test
    void standaloneCommandsAreRegisteredWithoutReplacingOthers() {
        for (String label : List.of("togglepvp", "pvpstats", "pvppoints", "pvptop", "bounty", "grace", "deathback", "killrecaps",
                "deathmessages", "stattrak", "sellhead", "potionstack", "pvp-interval", "oldpvp", "tag")) {
            Command command = server.getCommandMap().getCommand(label);
            assertNotNull(command, "/" + label + " registered");
            assertNotNull(server.getCommandMap().getCommand("ultimatecombat:" + label), "namespaced /ultimatecombat:" + label);
        }
    }
}
