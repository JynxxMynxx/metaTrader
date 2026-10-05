package dev.zenith.trader.command;

/** Sends interactive command feedback while keeping bulk execution concise. */
final class CommandFeedback {
    private static final ThreadLocal<Boolean> BULK_EXECUTION = ThreadLocal.withInitial(() -> false);

    private CommandFeedback() {}

    static void setBulkExecution(boolean value) {
        BULK_EXECUTION.set(value);
    }

    static void success(com.mojang.brigadier.context.CommandContext<com.zenith.command.api.CommandContext> context, String title) {
        if (!BULK_EXECUTION.get()) context.getSource().getEmbed().title(title);
    }

    static void success(com.mojang.brigadier.context.CommandContext<com.zenith.command.api.CommandContext> context, String title, String description) {
        if (!BULK_EXECUTION.get()) context.getSource().getEmbed().title(title).description(description);
    }
}
