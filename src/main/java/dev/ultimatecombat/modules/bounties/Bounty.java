package dev.ultimatecombat.modules.bounties;

import java.util.UUID;

/**
 * An active bounty placed on a player.
 *
 * @param setter {@code null} when placed by the console
 */
public record Bounty(long id, UUID target, String targetName, UUID setter, String setterName, double amount, double tax,
                    boolean anonymous, long createdAt, long expiresAt) {
}
