package dev.ultimatecombat.core.message;

import java.util.List;

/**
 * Parsed message entry from a language file. A message may contain any combination of chat
 * lines, an action bar, a title/subtitle and a sound preset. An empty message is disabled.
 *
 * <pre>
 * key: "Simple chat line"                # chat only
 * key: ["line 1", "line 2"]             # multi-line chat
 * key: ""                                # disabled
 * key:
 *   chat: "Chat line"                    # optional
 *   actionbar: "Action bar text"         # optional
 *   title: "Big title"                   # optional
 *   subtitle: "Smaller subtitle"         # optional
 *   fade-in: 10                          # ticks, optional
 *   stay: 40                             # ticks, optional
 *   fade-out: 10                         # ticks, optional
 *   sound: combat-start                  # preset name from sounds.yml, optional
 *   prefix: true                         # prepend the global prefix to chat lines
 * </pre>
 */
public record Message(List<String> chat, String actionbar, String title, String subtitle,
                      int fadeIn, int stay, int fadeOut, String sound, boolean prefix) {

    public static final Message EMPTY = new Message(List.of(), "", "", "", 10, 40, 10, "", true);

    public boolean isEmpty() {
        return chat.isEmpty() && actionbar.isEmpty() && title.isEmpty() && subtitle.isEmpty() && sound.isEmpty();
    }

    public boolean hasTitle() {
        return !title.isEmpty() || !subtitle.isEmpty();
    }
}
