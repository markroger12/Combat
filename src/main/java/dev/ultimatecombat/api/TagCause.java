package dev.ultimatecombat.api;

/**
 * Why a combat tag started or was refreshed.
 */
public enum TagCause {
    /** The player attacked someone. */
    ATTACKER,
    /** The player was attacked. */
    VICTIM,
    /** An administrator used /uc tag. */
    COMMAND,
    /** A player with a bounty was tagged by the bounty system. */
    BOUNTY,
    /** Another plugin used the API. */
    API
}
