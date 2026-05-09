package engine.tb;

import core.BitboardState;
import model.Color;
import model.Piece;

import java.io.IOException;
import java.nio.file.*;
import java.util.logging.Logger;

/**
 * Interface avec les tablebases Syzygy (format .rtbw / .rtbz).
 *
 * <h2>Architecture</h2>
 * Les tablebases Syzygy couvrent toutes les positions avec ≤ N pièces.
 * Les fichiers téléchargeables couvrent :
 * <ul>
 *   <li>3-4 pièces : ~1 Mo — indispensable, embarqué dans les tests</li>
 *   <li>5 pièces   : ~1 Go — recommandé</li>
 *   <li>6 pièces   : ~70 Go — pour les engines top-niveau</li>
 *   <li>7 pièces   : ~150 Go — Stockfish/Leela</li>
 * </ul>
 *
 * <h2>Intégration dans la recherche</h2>
 * {@link engine.search.AlphaBetaSearch} appelle {@link #probe(BitboardState)}
 * quand le nombre de pièces tombe à {@code ≤ maxPieces}. Si la sonde retourne
 * un {@link WDL} connu, le score est retourné directement sans évaluation statique
 * ni continuation de la recherche.
 *
 * <h2>Implémentation actuelle</h2>
 * Cette classe fournit l'interface complète et l'intégration dans le moteur.
 * La lecture binaire des fichiers Syzygy est désormais implémentée en Java pur :
 * <ul>
 *   <li>{@link SyzygyPieceLayout} : calcul du nom canonique et de l'index de position</li>
 *   <li>{@link SyzygyRtbwParser}  : lecture des fichiers .rtbw (mémoire mappée, nibbles)</li>
 * </ul>
 * En l'absence de fichiers, les sondes intégrées couvrent KQvK, KRvK, KBBvK, KBNvK
 * et les cas de matériel insuffisant. Pour les autres finales, {@link WDL#unknown()} est
 * retourné et le moteur fonctionne normalement sans dégradation.
 *
 * <h2>Détection automatique des fichiers</h2>
 * Au démarrage, le moteur scanne le répertoire configuré pour les fichiers .rtbw
 * et détermine automatiquement le nombre maximum de pièces couvertes.
 *
 * <h2>Source</h2>
 * Format Syzygy : <a href="https://github.com/syzygy1/tb">github.com/syzygy1/tb</a><br>
 * Fathom (binding C) : <a href="https://github.com/jnortiz/Fathom">github.com/jnortiz/Fathom</a>
 */
public final class SyzygyTablebase {

    private static final Logger LOG = Logger.getLogger(SyzygyTablebase.class.getName());

    /**
     * Nombre de pièces maximal couvert par les built-ins intégrés (sans fichiers).
     * Les built-ins couvrent KQvK, KRvK, KBBvK, KBNvK et les cas de matériel insuffisant.
     */
    private static final int BUILTIN_MAX_PIECES = 4;

    /** Nombre de pièces maximal pour lequel des fichiers ont été détectés. */
    private final int maxPieces;

    /** Répertoire contenant les fichiers .rtbw / .rtbz. */
    private final Path tbDir;

    /** True si des fichiers Syzygy ont été détectés et peuvent être utilisés. */
    private final boolean available;

    /** True si les sondes intégrées (built-in) sont actives mais sans fichiers. */
    private final boolean builtInOnly;

    // ── Construction ──────────────────────────────────────────────────────────

    /**
     * Crée une instance avec détection automatique des fichiers.
     *
     * @param tbDirectory répertoire contenant les fichiers Syzygy (.rtbw / .rtbz)
     *                    Peut être null ou inexistant — dans ce cas la tablebase est désactivée.
     */
    public SyzygyTablebase(Path tbDirectory) {
        this.tbDir = tbDirectory;
        if (tbDirectory == null || !Files.isDirectory(tbDirectory)) {
            this.maxPieces   = BUILTIN_MAX_PIECES;
            this.available   = false;
            this.builtInOnly = true;
            LOG.info("Tablebases Syzygy : desactivees (repertoire non fourni ou inexistant) - built-ins actifs");
            return;
        }

        int detected = detectMaxPieces(tbDirectory);
        this.maxPieces   = detected;
        this.available   = detected >= 3;
        this.builtInOnly = false;

        if (available) {
            LOG.info("Tablebases Syzygy : " + detected + " pieces max detectees dans " + tbDirectory);
        } else {
            LOG.info("Tablebases Syzygy : aucun fichier .rtbw detecte dans " + tbDirectory);
        }
    }

    /** Instance désactivée (pas de tablebases configurées). */
    public static SyzygyTablebase disabled() {
        return new SyzygyTablebase(null);
    }

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Sonde la tablebase pour la position donnée.
     *
     * <p>Retourne {@link WDL#unknown()} si :
     * <ul>
     *   <li>Les tablebases ne sont pas disponibles et la position dépasse BUILTIN_MAX_PIECES</li>
     *   <li>Le nombre de pièces dépasse {@code maxPieces}</li>
     * </ul>
     *
     * @param state position à évaluer
     * @return résultat WDL ou {@link WDL#unknown()}
     */
    public WDL probe(BitboardState state) {
        int pieceCount = Long.bitCount(state.getAllOccupancy());

        if (builtInOnly) {
            // Pas de fichiers : on tente uniquement les sondes intégrées,
            // mais seulement pour les positions ≤ BUILTIN_MAX_PIECES.
            if (pieceCount > BUILTIN_MAX_PIECES) return WDL.unknown();
            return probeBuiltIn(state);
        }
        if (!available) return WDL.unknown();
        if (pieceCount > maxPieces) return WDL.unknown();
        return probeInternal(state);
    }

    /**
     * Indique si les tablebases sont disponibles (fichiers réels détectés).
     */
    public boolean isAvailable() { return available; }

    /**
     * Retourne le nombre maximal de pièces couvert par les tablebases chargées.
     * Retourne BUILTIN_MAX_PIECES si seuls les built-ins sont actifs.
     */
    public int getMaxPieces() { return maxPieces; }

    /**
     * Indique si une position donnée peut être sondée (nombre de pièces OK).
     *
     * <p>Pour les built-ins : seulement si pieceCount ≤ BUILTIN_MAX_PIECES.
     * Pour les fichiers réels : seulement si available et pieceCount ≤ maxPieces.
     */
    public boolean canProbe(BitboardState state) {
        int pieceCount = Long.bitCount(state.getAllOccupancy());
        if (builtInOnly) {
            // Les built-ins ne couvrent que les positions ≤ BUILTIN_MAX_PIECES pièces
            return pieceCount <= BUILTIN_MAX_PIECES;
        }
        if (!available) return false;
        return pieceCount <= maxPieces;
    }

    // ── Score pour l'évaluateur statique ─────────────────────────────────────

    /**
     * Convertit une sonde en score centipions pour {@link engine.evaluation.PositionEvaluator}.
     * Retourne null si la sonde est inconnue (laisser l'évaluateur normal travailler).
     *
     * @param state    position
     * @param ply      profondeur depuis la racine
     * @param matScore valeur de mat du moteur
     * @return score centipions ou null
     */
    public Integer probeScore(BitboardState state, int ply, int matScore) {
        WDL result = probe(state);
        if (!result.isKnown()) return null;

        int rawScore = result.toScore(ply, matScore);

        // Ajustement pour la règle des 50 coups :
        // si on est dans un WIN mais DTZ proche de 50, on préfère les coups
        // qui réinitialisent le compteur (prise, avance de pion).
        // Cette logique fine est gérée par AlphaBetaSearch via le DTZ.
        return rawScore;
    }

    // ── Implémentation interne ────────────────────────────────────────────────

    /**
     * Implémentation de la sonde réelle.
     *
     * <p><b>Note d'implémentation</b> : pour une intégration complète avec les fichiers
     * binaires Syzygy, deux approches sont possibles :
     *
     * <ol>
     * <li><b>JNI vers Fathom</b> (recommandé) : compiler la bibliothèque C Fathom
     *     (github.com/jnortiz/Fathom), générer un wrapper JNI, charger la lib native.
     *     Performance optimale, maintenance minimale.</li>
     * <li><b>Java pur</b> : parser les fichiers .rtbw directement. Format documenté
     *     dans syzygy1/tb/src/tbprobe.c. Lourd à implémenter (~500 lignes).</li>
     * </ol>
     *
     * <p>Sans l'une ou l'autre de ces intégrations, cette méthode retourne
     * {@link WDL#unknown()} et le moteur fonctionne normalement.
     *
     * <p>Pour activer, décommenter l'une des options ci-dessous et adapter le build.
     */
    private WDL probeInternal(BitboardState state) {
        // ── Étape 1 : fallback sur les sondes intégrées (finales triviales) ────
        // Permet de fonctionner même sans fichiers .rtbw sur le disque.
        WDL builtIn = probeBuiltIn(state);
        if (builtIn.isKnown()) return builtIn;

        // ── Étape 2 : parser binaire Java pur (fichiers .rtbw) ─────────────────
        return probeFromFile(state);
    }

    /**
     * Sonde un fichier .rtbw réel via le parser Java pur {@link SyzygyRtbwParser}.
     *
     * <p>Pipeline :
     * <ol>
     *   <li>Détermination du nom de fichier canonique via {@link SyzygyPieceLayout#getTableName}</li>
     *   <li>Vérification de l'existence du fichier dans {@link #tbDir}</li>
     *   <li>Calcul de l'index de position via {@link SyzygyPieceLayout#computeIndex}</li>
     *   <li>Lecture de la valeur WDL via {@link SyzygyRtbwParser#readWDL}</li>
     *   <li>Inversion du WDL si les couleurs sont permutées par rapport au fichier</li>
     * </ol>
     *
     * <p>Gestion des couleurs : le fichier est nommé selon le côté fort en premier.
     * Si les blancs sont le côté faible, le WDL retourné par le fichier est du point
     * de vue du côté fort — il faut donc l'inverser pour le camp à jouer.
     */
    private WDL probeFromFile(BitboardState state) {
        if (tbDir == null) return WDL.unknown();

        // ── 1. Nom canonique du fichier ───────────────────────────────────────
        String tableName = SyzygyPieceLayout.getTableName(state);
        if (tableName == null) return WDL.unknown();

        java.nio.file.Path rtbwPath = tbDir.resolve(tableName + ".rtbw");
        if (!java.nio.file.Files.exists(rtbwPath)) {
            LOG.fine("Fichier absent : " + rtbwPath.getFileName());
            return WDL.unknown();
        }

        // ── 2. Côté fort = celui nommé en premier dans le fichier ─────────────
        boolean whiteIsStrong = SyzygyPieceLayout.whiteIsStrongSide(state);
        boolean stmIsStrong   = (state.getSideToMove() == Color.WHITE) == whiteIsStrong;

        // ── 3. Index de position ──────────────────────────────────────────────
        long index = SyzygyPieceLayout.computeIndex(state, whiteIsStrong);
        if (index < 0) {
            LOG.fine("Index négatif pour " + tableName + " — position hors couverture");
            return WDL.unknown();
        }

        // ── 4. Lecture WDL ────────────────────────────────────────────────────
        WDL raw = SyzygyRtbwParser.readWDL(rtbwPath, index, stmIsStrong);
        if (!raw.isKnown()) return WDL.unknown();

        // ── 5. Ajustement : le WDL est du point de vue du camp à jouer ────────
        // Le fichier stocke la valeur du côté fort. Si le camp à jouer n'est pas
        // le côté fort, on doit inverser (win ↔ loss, blessed ↔ cursed).
        // Cette inversion est déjà gérée par stmIsStrong dans readWDL — on
        // retourne directement le résultat.
        return raw;
    }

    /**
     * Sondes intégrées pour les finales les plus simples.
     * Couvre : KQvK, KRvK, KBBvK, KBNvK (mat forcé),
     *          KBvK, KNvK (matériel insuffisant → nulle).
     * Ces positions peuvent être détectées sans fichiers binaires.
     *
     * <p>IMPORTANT : cette méthode ne doit être appelée que pour les positions
     * ≤ BUILTIN_MAX_PIECES pièces (vérification faite dans {@link #probe}).
     */
    private WDL probeBuiltIn(BitboardState state) {
        // Compter les pièces par type (hors rois)
        int wQ = Long.bitCount(state.getBitboard(Color.WHITE, Piece.QUEEN));
        int wR = Long.bitCount(state.getBitboard(Color.WHITE, Piece.ROOK));
        int wB = Long.bitCount(state.getBitboard(Color.WHITE, Piece.BISHOP));
        int wN = Long.bitCount(state.getBitboard(Color.WHITE, Piece.KNIGHT));
        int wP = Long.bitCount(state.getBitboard(Color.WHITE, Piece.PAWN));
        int bQ = Long.bitCount(state.getBitboard(Color.BLACK, Piece.QUEEN));
        int bR = Long.bitCount(state.getBitboard(Color.BLACK, Piece.ROOK));
        int bB = Long.bitCount(state.getBitboard(Color.BLACK, Piece.BISHOP));
        int bN = Long.bitCount(state.getBitboard(Color.BLACK, Piece.KNIGHT));
        int bP = Long.bitCount(state.getBitboard(Color.BLACK, Piece.PAWN));

        boolean whiteSide = state.getSideToMove() == Color.WHITE;

        // ── Matériel insuffisant → nulle ──────────────────────────────────────
        // KvK, KBvK, KNvK
        boolean wInsuff = (wQ + wR + wP == 0) && (wB + wN <= 1);
        boolean bInsuff = (bQ + bR + bP == 0) && (bB + bN <= 1);
        if (wInsuff && bInsuff) return WDL.draw();

        // ── KQvK ─────────────────────────────────────────────────────────────
        if (wQ == 1 && wR == 0 && wB == 0 && wN == 0 && wP == 0
                    && bQ == 0 && bR == 0 && bB == 0 && bN == 0 && bP == 0) {
            return whiteSide ? WDL.win() : WDL.loss();
        }
        if (bQ == 1 && bR == 0 && bB == 0 && bN == 0 && bP == 0
                    && wQ == 0 && wR == 0 && wB == 0 && wN == 0 && wP == 0) {
            return whiteSide ? WDL.loss() : WDL.win();
        }

        // ── KRvK ─────────────────────────────────────────────────────────────
        if (wR == 1 && wQ == 0 && wB == 0 && wN == 0 && wP == 0
                    && bQ == 0 && bR == 0 && bB == 0 && bN == 0 && bP == 0) {
            return whiteSide ? WDL.win() : WDL.loss();
        }
        if (bR == 1 && bQ == 0 && bB == 0 && bN == 0 && bP == 0
                    && wQ == 0 && wR == 0 && wB == 0 && wN == 0 && wP == 0) {
            return whiteSide ? WDL.loss() : WDL.win();
        }

        // ── KBBvK (deux fous) ─────────────────────────────────────────────────
        if (wB == 2 && wQ == 0 && wR == 0 && wN == 0 && wP == 0
                    && bQ == 0 && bR == 0 && bB == 0 && bN == 0 && bP == 0) {
            return whiteSide ? WDL.win() : WDL.loss();
        }
        if (bB == 2 && bQ == 0 && bR == 0 && bN == 0 && bP == 0
                    && wQ == 0 && wR == 0 && wB == 0 && wN == 0 && wP == 0) {
            return whiteSide ? WDL.loss() : WDL.win();
        }

        // ── KBNvK (fou + cavalier) → mat forcé mais difficile ────────────────
        if (wB == 1 && wN == 1 && wQ == 0 && wR == 0 && wP == 0
                     && bQ == 0 && bR == 0 && bB == 0 && bN == 0 && bP == 0) {
            return whiteSide ? WDL.win() : WDL.loss();
        }
        if (bB == 1 && bN == 1 && bQ == 0 && bR == 0 && bP == 0
                     && wQ == 0 && wR == 0 && wB == 0 && wN == 0 && wP == 0) {
            return whiteSide ? WDL.loss() : WDL.win();
        }

        // ── KRvKB (tour vs fou) → DRAW avec jeu parfait ───────────────────────
        if (wR == 1 && wB == 0 && wQ == 0 && wN == 0 && wP == 0
                    && bB == 1 && bR == 0 && bQ == 0 && bN == 0 && bP == 0) {
            return WDL.draw();
        }
        if (bR == 1 && bB == 0 && bQ == 0 && bN == 0 && bP == 0
                    && wB == 1 && wR == 0 && wQ == 0 && wN == 0 && wP == 0) {
            return WDL.draw();
        }

        // ── KRvKN (tour vs cavalier) → DRAW avec jeu parfait ─────────────────
        if (wR == 1 && wN == 0 && wQ == 0 && wB == 0 && wP == 0
                    && bN == 1 && bR == 0 && bQ == 0 && bB == 0 && bP == 0) {
            return WDL.draw();
        }
        if (bR == 1 && bN == 0 && bQ == 0 && bB == 0 && bP == 0
                    && wN == 1 && wR == 0 && wQ == 0 && wB == 0 && wP == 0) {
            return WDL.draw();
        }

        // ── KNvKB / KBvKN (pièces mineures différentes) → DRAW ───────────────
        if (wN == 1 && wB == 0 && wQ == 0 && wR == 0 && wP == 0
                    && bB == 1 && bN == 0 && bQ == 0 && bR == 0 && bP == 0) {
            return WDL.draw();
        }
        if (bN == 1 && bB == 0 && bQ == 0 && bR == 0 && bP == 0
                    && wB == 1 && wN == 0 && wQ == 0 && wR == 0 && wP == 0) {
            return WDL.draw();
        }

        // ── KRvKR (tours égales) → DRAW ───────────────────────────────────────
        if (wR == 1 && wQ == 0 && wB == 0 && wN == 0 && wP == 0
                    && bR == 1 && bQ == 0 && bB == 0 && bN == 0 && bP == 0) {
            return WDL.draw();
        }

        // ── KQvKQ (dames égales) → DRAW ───────────────────────────────────────
        if (wQ == 1 && wR == 0 && wB == 0 && wN == 0 && wP == 0
                    && bQ == 1 && bR == 0 && bB == 0 && bN == 0 && bP == 0) {
            return WDL.draw();
        }

        // ── KBvKB (même ou couleurs opposées) → DRAW ─────────────────────────
        if (wB == 1 && wQ == 0 && wR == 0 && wN == 0 && wP == 0
                    && bB == 1 && bQ == 0 && bR == 0 && bN == 0 && bP == 0) {
            return WDL.draw();
        }

        // ── KQvKR (dame vs tour) → WIN pour le camp avec la dame ─────────────
        // La dame gagne théoriquement contre la tour seule.
        // Quelques rares positions sont CURSED_WIN (règle des 50 coups),
        // mais elles seront couvertes par le parser binaire quand il sera implémenté.
        if (wQ == 1 && wR == 0 && wB == 0 && wN == 0 && wP == 0
                    && bQ == 0 && bR == 1 && bB == 0 && bN == 0 && bP == 0) {
            return whiteSide ? WDL.win() : WDL.loss();
        }
        if (bQ == 1 && bR == 0 && bB == 0 && bN == 0 && bP == 0
                    && wQ == 0 && wR == 1 && wB == 0 && wN == 0 && wP == 0) {
            return whiteSide ? WDL.loss() : WDL.win();
        }

        // ── KPvK (pion vs rien) → WIN ou DRAW selon position ─────────────────
        // Heuristique basée sur les règles classiques des fins de partie.
        if (wP == 1 && wQ == 0 && wR == 0 && wB == 0 && wN == 0
                    && bQ == 0 && bR == 0 && bB == 0 && bN == 0 && bP == 0) {
            return probeKPvK(state, Color.WHITE, whiteSide);
        }
        if (bP == 1 && bQ == 0 && bR == 0 && bB == 0 && bN == 0
                    && wQ == 0 && wR == 0 && wB == 0 && wN == 0 && wP == 0) {
            return probeKPvK(state, Color.BLACK, whiteSide);
        }

        return WDL.unknown();
    }

    /**
     * Heuristique pour KPvK (pion vs rien).
     * <ul>
     *   <li>Pion de tour (colonne a ou h) : DRAW si le roi adverse est dans le coin devant</li>
     *   <li>Autres colonnes : WIN si le roi fort est devant le pion (au moins 1 rang d'avance)</li>
     *   <li>Sinon : unknown (laisser la recherche décider)</li>
     * </ul>
     */
    private WDL probeKPvK(BitboardState state, Color pawnColor, boolean whiteSide) {
        boolean pawnIsWhite = (pawnColor == Color.WHITE);

        long pawnBB  = state.getBitboard(pawnColor, Piece.PAWN);
        long sKingBB = state.getBitboard(pawnColor, Piece.KING);
        long wKingBB = state.getBitboard(pawnColor.opposite(), Piece.KING);

        int pawnSq   = Long.numberOfTrailingZeros(pawnBB);
        int sKingSq  = Long.numberOfTrailingZeros(sKingBB);
        int wKingSq  = Long.numberOfTrailingZeros(wKingBB);

        int pawnFile  = pawnSq & 7;
        int pawnRank  = pawnSq >> 3;  // 0=rang 1, 7=rang 8
        int sKingRank = sKingSq >> 3;

        // Pion de tour : DRAW si roi adverse dans le coin de promotion
        if (pawnFile == 0 || pawnFile == 7) {
            int promSq    = pawnIsWhite ? (56 + pawnFile) : pawnFile;
            int wKingFile = wKingSq & 7;
            int wKingRank = wKingSq >> 3;
            int promFile  = promSq & 7;
            int promRank  = promSq >> 3;
            int dist = Math.max(Math.abs(wKingFile - promFile), Math.abs(wKingRank - promRank));
            if (dist <= 1) return WDL.draw();
            return WDL.unknown();
        }

        // Colonnes centrales (b-g) :
        // WIN si le roi fort est DEVANT le pion (rang strictement supérieur pour les blancs)
        if (pawnIsWhite) {
            if (sKingRank > pawnRank) {
                return whiteSide ? WDL.win() : WDL.loss();
            }
        } else {
            if (sKingRank < pawnRank) {
                return whiteSide ? WDL.loss() : WDL.win();
            }
        }

        return WDL.unknown();
    }

    // ── Détection des fichiers ────────────────────────────────────────────────

    /**
     * Scanne le répertoire pour détecter le nombre maximal de pièces couvert.
     * Cherche des fichiers comme KQvK.rtbw (3 pièces), KRPvKP.rtbw (5 pièces), etc.
     * La heuristique : nombre de caractères alphabétiques dans le nom de fichier
     * (avant le 'v') + ceux après le 'v' = nombre total de pièces.
     */
    private static int detectMaxPieces(Path dir) {
        int max = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.rtbw")) {
            for (Path p : stream) {
                String name = p.getFileName().toString().replace(".rtbw", "");
                int count = 0;
                for (char c : name.toCharArray()) {
                    if (Character.isLetter(c)) count++;
                }
                max = Math.max(max, count);
            }
        } catch (IOException e) {
            LOG.warning("Erreur lors du scan du répertoire tablebase : " + e.getMessage());
        }
        return max;
    }
}
