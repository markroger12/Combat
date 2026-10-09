package dev.ultimatecombat.core.command;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Leaf command built from lambdas.
 */
public final class SimpleCommand extends UCCommand {

    private final String usage;
    private final Consumer<CommandContext> action;
    private final Function<CommandContext, List<String>> completer;

    public SimpleCommand(String name, String usage, String permission, boolean playerOnly, Consumer<CommandContext> action,
                         Function<CommandContext, List<String>> completer, String... aliases) {
        super(name, permission, playerOnly, aliases);
        this.usage = usage;
        this.action = action;
        this.completer = completer;
    }

    public SimpleCommand(String name, String usage, String permission, boolean playerOnly, Consumer<CommandContext> action) {
        this(name, usage, permission, playerOnly, action, null);
    }

    @Override
    public String usage() {
        return usage;
    }

    @Override
    protected void run(CommandContext ctx) {
        action.accept(ctx);
    }

    @Override
    protected List<String> tab(CommandContext ctx) {
        return completer == null ? List.of() : completer.apply(ctx);
    }
}
