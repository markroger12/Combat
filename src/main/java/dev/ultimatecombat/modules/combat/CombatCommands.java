package dev.ultimatecombat.modules.combat;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.api.CombatType;
import dev.ultimatecombat.api.TagCause;
import dev.ultimatecombat.api.UntagReason;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.command.UCCommand;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * /uc tag, /uc untag and the stand-alone /tag status command.
 */
final class CombatCommands {

    private CombatCommands() {
    }

    static void register(UltimateCombatPlugin plugin, CombatModule module) {
        UCCommand root = plugin.commands().root();
        root.child(new SimpleCommand("tag", "tag <player> [seconds] [pvp|pve]", "ultimatecombat.tag", false,
                ctx -> tag(plugin, module, ctx), ctx -> switch (ctx.size()) {
                    case 1 -> UCCommand.onlineNames();
                    case 2 -> List.of("10", "20", "30", "60");
                    case 3 -> List.of("pvp", "pve");
                    default -> List.of();
                }));
        root.child(new SimpleCommand("untag", "untag <player|all>", "ultimatecombat.untag", false,
                ctx -> untag(plugin, module, ctx), ctx -> {
                    List<String> out = new ArrayList<>(UCCommand.onlineNames());
                    out.add("all");
                    return ctx.size() == 1 ? out : List.of();
                }));
        SimpleCommand status = new SimpleCommand("combat", "combat [player]", "ultimatecombat.combat.status", false,
                ctx -> status(plugin, module, ctx), ctx -> ctx.size() == 1 && ctx.has("ultimatecombat.combat.status.others")
                ? UCCommand.onlineNames() : List.of(), "combattime");
        root.child(status);
        plugin.commands().standalone("tag", status);
    }

    private static void tag(UltimateCombatPlugin plugin, CombatModule module, CommandContext ctx) {
        if (!module.guard(ctx)) {
            return;
        }
        if (ctx.size() < 1) {
            ctx.reply("general.usage", "usage", "/uc tag <player> [seconds] [pvp|pve]");
            return;
        }
        Player target = ctx.onlinePlayer(0);
        if (target == null) {
            return;
        }
        long millis = 0;
        if (ctx.size() >= 2) {
            Integer secs = ctx.intArg(1, 1, 86_400);
            if (secs == null) {
                return;
            }
            millis = secs * 1000L;
        }
        CombatType type = "pve".equalsIgnoreCase(ctx.arg(2)) ? CombatType.PVE : CombatType.PVP;
        long duration = millis;
        plugin.scheduler().executeEntity(target, () -> {
            boolean ok = module.tag(target, null, type, TagCause.COMMAND, duration);
            ctx.reply(ok ? "combat.admin.tagged" : "combat.admin.tag-failed", "player", target.getName(),
                    "time", module.seconds(module.remainingMillis(target)), "type", type.name());
        });
    }

    private static void untag(UltimateCombatPlugin plugin, CombatModule module, CommandContext ctx) {
        if (!module.guard(ctx)) {
            return;
        }
        if (ctx.size() < 1) {
            ctx.reply("general.usage", "usage", "/uc untag <player|all>");
            return;
        }
        if (ctx.arg(0).equalsIgnoreCase("all")) {
            int count = 0;
            for (CombatTag tag : new ArrayList<>(module.tags())) {
                Player p = Bukkit.getPlayer(tag.player());
                if (p != null) {
                    plugin.scheduler().executeEntity(p, () -> module.untag(p, UntagReason.COMMAND, true));
                    count++;
                }
            }
            ctx.reply("combat.admin.untagged-all", "count", count);
            return;
        }
        Player target = ctx.onlinePlayer(0);
        if (target == null) {
            return;
        }
        if (!module.isTagged(target)) {
            ctx.reply("combat.admin.not-tagged", "player", target.getName());
            return;
        }
        plugin.scheduler().executeEntity(target, () -> module.untag(target, UntagReason.COMMAND, true));
        ctx.reply("combat.admin.untagged", "player", target.getName());
    }

    private static void status(UltimateCombatPlugin plugin, CombatModule module, CommandContext ctx) {
        if (!module.guard(ctx)) {
            return;
        }
        Player target;
        if (ctx.size() >= 1) {
            if (!ctx.has("ultimatecombat.combat.status.others")) {
                ctx.reply("general.no-permission");
                return;
            }
            target = ctx.onlinePlayer(0);
            if (target == null) {
                return;
            }
        } else if (ctx.isPlayer()) {
            target = ctx.player();
        } else {
            ctx.reply("general.player-only");
            return;
        }
        CombatTag tag = module.tag(target);
        boolean self = target == ctx.sender();
        if (tag == null || tag.remainingMillis() <= 0) {
            ctx.reply(self ? "combat.status.not-in-combat" : "combat.status.other-not-in-combat", "player", target.getName());
            return;
        }
        List<String> opponents = module.opponentNames(target);
        ctx.reply(self ? "combat.status.in-combat" : "combat.status.other-in-combat", "player", target.getName(),
                "time", module.seconds(tag.remainingMillis()), "type", tag.type().name(),
                "opponents", opponents.isEmpty() ? plugin.messages().settings().none() : String.join(", ", opponents));
    }
}
