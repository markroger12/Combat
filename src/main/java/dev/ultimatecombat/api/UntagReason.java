package dev.ultimatecombat.api;

/**
 * Why a combat tag ended.
 */
public enum UntagReason {
    EXPIRED,
    DEATH,
    OPPONENT_DIED,
    QUIT,
    COMMAND,
    WORLD_CHANGE,
    PLUGIN_DISABLE,
    API,
    PVP_INTERVAL
}
