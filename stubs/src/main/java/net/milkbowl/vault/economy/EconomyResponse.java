package net.milkbowl.vault.economy;

/** Compile-time stub of Vault's economy response (subset used by UltimateCombat). */
public class EconomyResponse {

    public final String errorMessage;

    public EconomyResponse(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public boolean transactionSuccess() {
        throw new UnsupportedOperationException("stub");
    }
}
