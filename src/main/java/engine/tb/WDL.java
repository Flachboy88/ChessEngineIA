package engine.tb;

/**
 * Résultat d'une sonde de tablebase Syzygy.
 *
 * <h2>Valeurs WDL (Win/Draw/Loss)</h2>
 * <pre>
 *   WIN   (+2) : position gagnante pour le camp à jouer
 *   CURSED_WIN (+1) : gagné mais nul par la règle des 50 coups selon DTZ
 *   DRAW  (0)  : nulle
 *   BLESSED_LOSS (-1) : perdu mais sauvé par la règle des 50 coups
 *   LOSS  (-2) : position perdue pour le camp à jouer
 *   UNKNOWN    : position non couverte par les tablebases chargées
 * </pre>
 *
 * <h2>DTZ (Distance To Zeroing)</h2>
 * Distance en demi-coups jusqu'à la prochaine avance de pion ou prise.
 * Utilisé pour éviter les nuls par la règle des 50 coups lors d'un jeu théoriquement gagnant.
 * -1 si non disponible.
 */
public final class WDL {

    public static final int WIN          =  2;
    public static final int CURSED_WIN   =  1;
    public static final int DRAW         =  0;
    public static final int BLESSED_LOSS = -1;
    public static final int LOSS         = -2;
    public static final int UNKNOWN      = Integer.MIN_VALUE;

    /** Résultat WDL de ce nœud. */
    public final int wdl;

    /** Distance to Zeroing (-1 si inconnue). */
    public final int dtz;

    private WDL(int wdl, int dtz) {
        this.wdl = wdl;
        this.dtz = dtz;
    }

    // ── Factories ─────────────────────────────────────────────────────────────

    public static WDL win()         { return new WDL(WIN,          -1); }
    public static WDL cursedWin()   { return new WDL(CURSED_WIN,   -1); }
    public static WDL draw()        { return new WDL(DRAW,         -1); }
    public static WDL blessedLoss() { return new WDL(BLESSED_LOSS, -1); }
    public static WDL loss()        { return new WDL(LOSS,         -1); }
    public static WDL unknown()     { return new WDL(UNKNOWN,      -1); }
    public static WDL of(int wdl, int dtz) { return new WDL(wdl, dtz); }

    // ── Prédicats ─────────────────────────────────────────────────────────────

    public boolean isKnown()   { return wdl != UNKNOWN; }
    public boolean isWin()     { return wdl == WIN; }
    public boolean isDraw()    { return wdl == DRAW; }
    public boolean isLoss()    { return wdl == LOSS; }

    /**
     * Convertit le WDL en score centipions pour l'évaluateur.
     * Utilisé quand la recherche alpha-bêta atteint une position en tablebase.
     *
     * @param ply profondeur depuis la racine (pour normaliser les scores de mat)
     * @param mateScore valeur de mat (ex: 1_000_000)
     */
    public int toScore(int ply, int mateScore) {
        return switch (wdl) {
            case WIN          ->  mateScore - ply;
            case CURSED_WIN   ->  1;   // gagné mais quasi-nul selon les 50 coups
            case DRAW         ->  0;
            case BLESSED_LOSS -> -1;
            case LOSS         -> -(mateScore - ply);
            default           ->  0;
        };
    }

    @Override
    public String toString() {
        return switch (wdl) {
            case WIN          -> "WDL(WIN, dtz=" + dtz + ")";
            case CURSED_WIN   -> "WDL(CURSED_WIN, dtz=" + dtz + ")";
            case DRAW         -> "WDL(DRAW)";
            case BLESSED_LOSS -> "WDL(BLESSED_LOSS, dtz=" + dtz + ")";
            case LOSS         -> "WDL(LOSS, dtz=" + dtz + ")";
            default           -> "WDL(UNKNOWN)";
        };
    }
}
