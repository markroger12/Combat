package dev.ultimatecombat.core.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Isolated MiniMessage access. Only loaded after {@link Text} confirmed Adventure exists,
 * so Spigot servers (without Adventure) never load this class.
 */
final class MiniMessageBridge {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private MiniMessageBridge() {
    }

    static String toLegacy(String input) {
        // Legacy '&' codes are plain text to MiniMessage; Text#color translates them afterwards.
        Component component = MiniMessage.miniMessage().deserialize(input);
        return LEGACY.serialize(component);
    }
}
