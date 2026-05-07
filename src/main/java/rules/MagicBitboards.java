package rules;

import java.util.Random;

/**
 * Magic Bitboards pour fous et tours — lookups O(1) au lieu de ray-slides O(7).
 *
 * <h2>Génération des magics</h2>
 * Les nombres magiques sont générés au démarrage par recherche aléatoire (PEXT-like).
 * Cela prend ~50-200ms une seule fois à l'initialisation, mais garantit la correction.
 * Les magics hardcodés du CPW peuvent comporter des collisions silencieuses selon
 * l'implémentation — la génération évite ce risque.
 *
 * <h2>Utilisation</h2>
 * <pre>
 *   long occ = state.getAllOccupancy();
 *   long bishopAtk = MagicBitboards.getBishopAttacks(sq, occ);
 *   long rookAtk   = MagicBitboards.getRookAttacks(sq, occ);
 *   long queenAtk  = MagicBitboards.getQueenAttacks(sq, occ);
 * </pre>
 */
public final class MagicBitboards {

    private MagicBitboards() {}

    // ── Tables d'attaques précalculées ────────────────────────────────────────
    private static final long[][] BISHOP_ATTACKS = new long[64][];
    private static final long[][] ROOK_ATTACKS   = new long[64][];

    private static final long[] BISHOP_MASKS   = new long[64];
    private static final long[] ROOK_MASKS     = new long[64];
    private static final long[] BISHOP_MAGICS  = new long[64];
    private static final long[] ROOK_MAGICS    = new long[64];
    private static final int[]  BISHOP_SHIFTS  = new int[64];
    private static final int[]  ROOK_SHIFTS    = new int[64];

    // ── Initialisation statique ───────────────────────────────────────────────
    static {
        for (int sq = 0; sq < 64; sq++) {
            BISHOP_MASKS[sq]  = computeBishopMask(sq);
            ROOK_MASKS[sq]    = computeRookMask(sq);
            BISHOP_SHIFTS[sq] = 64 - Long.bitCount(BISHOP_MASKS[sq]);
            ROOK_SHIFTS[sq]   = 64 - Long.bitCount(ROOK_MASKS[sq]);
        }
        findAllMagics();
    }

    // ── API publique ──────────────────────────────────────────────────────────

    public static long getBishopAttacks(int sq, long occ) {
        long blockers = occ & BISHOP_MASKS[sq];
        int  idx      = (int)((blockers * BISHOP_MAGICS[sq]) >>> BISHOP_SHIFTS[sq]);
        return BISHOP_ATTACKS[sq][idx];
    }

    public static long getRookAttacks(int sq, long occ) {
        long blockers = occ & ROOK_MASKS[sq];
        int  idx      = (int)((blockers * ROOK_MAGICS[sq]) >>> ROOK_SHIFTS[sq]);
        return ROOK_ATTACKS[sq][idx];
    }

    public static long getQueenAttacks(int sq, long occ) {
        return getBishopAttacks(sq, occ) | getRookAttacks(sq, occ);
    }

    // ── Recherche de magics ───────────────────────────────────────────────────

    private static void findAllMagics() {
        Random rng = new Random(0xDEADBEEFCAFEL); // seed fixe = résultat reproductible
        for (int sq = 0; sq < 64; sq++) {
            findMagic(sq, false, rng);
            findMagic(sq, true,  rng);
        }
    }

    /**
     * Trouve un nombre magique pour {@code sq} (fou si {@code bishop}, tour sinon).
     * Stratégie : tirer des longs aléatoires avec peu de bits allumés jusqu'à trouver
     * un magic sans collision.
     */
    private static void findMagic(int sq, boolean bishop, Random rng) {
        long mask   = bishop ? BISHOP_MASKS[sq]  : ROOK_MASKS[sq];
        int  bits   = Long.bitCount(mask);
        int  shift  = bishop ? BISHOP_SHIFTS[sq] : ROOK_SHIFTS[sq];
        int  size   = 1 << bits;

        // Précalculer toutes les sous-configurations d'occupation et leurs attaques
        long[] occs    = new long[size];
        long[] attacks = new long[size];
        long   occ     = 0L;
        for (int i = 0; i < size; i++) {
            occs[i]    = occ;
            attacks[i] = bishop ? computeBishopAttacks(sq, occ) : computeRookAttacks(sq, occ);
            // Carry-Rippler : énumère tous les sous-ensembles de `mask`
            occ = (occ - mask) & mask;
        }

        long[] table = new long[size];

        // Recherche aléatoire du magic
        for (int attempt = 0; attempt < 100_000_000; attempt++) {
            // Candidat avec peu de bits → meilleure chance d'être un magic
            long magic = rng.nextLong() & rng.nextLong() & rng.nextLong();
            if (Long.bitCount((mask * magic) & 0xFF00000000000000L) < 6) continue;

            // Tester le candidat : pas de collision destructive
            boolean failed = false;
            java.util.Arrays.fill(table, 0L);
            for (int i = 0; i < size && !failed; i++) {
                int idx = (int)((occs[i] * magic) >>> shift);
                if (table[idx] == 0L) {
                    table[idx] = attacks[i];
                } else if (table[idx] != attacks[i]) {
                    failed = true; // Collision destructive
                }
            }

            if (!failed) {
                if (bishop) {
                    BISHOP_MAGICS[sq]  = magic;
                    BISHOP_ATTACKS[sq] = table.clone();
                } else {
                    ROOK_MAGICS[sq]  = magic;
                    ROOK_ATTACKS[sq] = table.clone();
                }
                return;
            }
        }
        throw new RuntimeException("Magic introuvable pour sq=" + sq + " bishop=" + bishop);
    }

    // ── Masques bloquants ─────────────────────────────────────────────────────

    private static long computeBishopMask(int sq) {
        long mask = 0L;
        int rank = sq / 8, file = sq % 8;
        for (int r = rank+1, f = file+1; r <= 6 && f <= 6; r++, f++) mask |= 1L << (r*8+f);
        for (int r = rank+1, f = file-1; r <= 6 && f >= 1; r++, f--) mask |= 1L << (r*8+f);
        for (int r = rank-1, f = file+1; r >= 1 && f <= 6; r--, f++) mask |= 1L << (r*8+f);
        for (int r = rank-1, f = file-1; r >= 1 && f >= 1; r--, f--) mask |= 1L << (r*8+f);
        return mask;
    }

    private static long computeRookMask(int sq) {
        long mask = 0L;
        int rank = sq / 8, file = sq % 8;
        for (int r = rank+1; r <= 6; r++) mask |= 1L << (r*8+file);
        for (int r = rank-1; r >= 1; r--) mask |= 1L << (r*8+file);
        for (int f = file+1; f <= 6; f++) mask |= 1L << (rank*8+f);
        for (int f = file-1; f >= 1; f--) mask |= 1L << (rank*8+f);
        return mask;
    }

    // ── Calcul des attaques réelles (uniquement à l'init) ─────────────────────

    private static long computeBishopAttacks(int sq, long blockers) {
        long attacks = 0L;
        int rank = sq / 8, file = sq % 8;
        for (int r = rank+1, f = file+1; r <= 7 && f <= 7; r++, f++) {
            attacks |= 1L << (r*8+f);
            if ((blockers & (1L << (r*8+f))) != 0) break;
        }
        for (int r = rank+1, f = file-1; r <= 7 && f >= 0; r++, f--) {
            attacks |= 1L << (r*8+f);
            if ((blockers & (1L << (r*8+f))) != 0) break;
        }
        for (int r = rank-1, f = file+1; r >= 0 && f <= 7; r--, f++) {
            attacks |= 1L << (r*8+f);
            if ((blockers & (1L << (r*8+f))) != 0) break;
        }
        for (int r = rank-1, f = file-1; r >= 0 && f >= 0; r--, f--) {
            attacks |= 1L << (r*8+f);
            if ((blockers & (1L << (r*8+f))) != 0) break;
        }
        return attacks;
    }

    private static long computeRookAttacks(int sq, long blockers) {
        long attacks = 0L;
        int rank = sq / 8, file = sq % 8;
        for (int r = rank+1; r <= 7; r++) {
            attacks |= 1L << (r*8+file);
            if ((blockers & (1L << (r*8+file))) != 0) break;
        }
        for (int r = rank-1; r >= 0; r--) {
            attacks |= 1L << (r*8+file);
            if ((blockers & (1L << (r*8+file))) != 0) break;
        }
        for (int f = file+1; f <= 7; f++) {
            attacks |= 1L << (rank*8+f);
            if ((blockers & (1L << (rank*8+f))) != 0) break;
        }
        for (int f = file-1; f >= 0; f--) {
            attacks |= 1L << (rank*8+f);
            if ((blockers & (1L << (rank*8+f))) != 0) break;
        }
        return attacks;
    }
}
