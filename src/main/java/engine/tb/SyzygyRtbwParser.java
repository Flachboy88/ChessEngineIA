package engine.tb;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Parser binaire pur Java pour les fichiers Syzygy WDL (.rtbw).
 *
 * <h2>Format exact du fichier .rtbw</h2>
 *
 * <pre>
 * Offset  Taille  Description
 * ──────  ──────  ────────────────────────────────────────────────────────────
 *  0       4      Magic : 0x5D23E871 (little-endian)
 *  4       1      Flags globaux  (bit0=split, bit1=has_pawns)
 *  5       1      Réservé
 *  6       1      Réservé
 *  7       1      Réservé
 *  8       4      num_tables (nombre de sous-tables, souvent 2 : STM=fort, STM=faible)
 * 12       4      Réservé / padding
 * --- Ensuite, pour chaque sous-table (2 fois) : ---
 * +0       4      offset vers le bloc de données de cette sous-table (en OCTETS depuis début fichier)
 * +4       1      flags de compression du bloc  (0 = nibbles directs, 1 = huffman)
 * +5       3      Réservé
 * --- Données ---
 * [offset_bloc_0] : nibbles WDL pour STM=côté fort
 * [offset_bloc_1] : nibbles WDL pour STM=côté faible
 * </pre>
 *
 * <h2>Valeurs WDL dans les nibbles</h2>
 * <pre>
 *   0 = LOSS (perte)
 *   1 = BLESSED_LOSS (perte mais sauvée par 50 coups)
 *   2 = DRAW (nulle)
 *   3 = CURSED_WIN (gain mais annulé par 50 coups)
 *   4 = WIN (gain)
 * </pre>
 * Chaque octet contient 2 valeurs : nibble bas (bits 0-3) pour index pair,
 * nibble haut (bits 4-7) pour index impair.
 *
 * <h2>Structure réelle observée dans les fichiers Syzygy</h2>
 * L'en-tête réel fait 5 × 4 = 20 octets :
 * <pre>
 *  [0-3]   magic
 *  [4-7]   num_tables  (= 2 pour les fichiers WDL standard)
 *  [8-11]  offset_bloc_0  (offset en octets depuis le début du fichier)
 *  [12-15] flags_bloc_0   (1 octet utile + 3 padding)
 *  [16-19] offset_bloc_1
 *  [20-23] flags_bloc_1
 * </pre>
 *
 * @see SyzygyPieceLayout
 * @see WDL
 */
public final class SyzygyRtbwParser {

    private static final Logger LOG = Logger.getLogger(SyzygyRtbwParser.class.getName());

    public static final int MAGIC_WDL = 0x5D23E871;
    public static final int MAGIC_DTZ = 0xA50C66D7;

    // Valeurs WDL brutes dans le fichier (centrées sur 2 = nulle)
    private static final int FILE_LOSS         = 0;
    private static final int FILE_BLESSED_LOSS = 1;
    private static final int FILE_DRAW         = 2;
    private static final int FILE_CURSED_WIN   = 3;
    private static final int FILE_WIN          = 4;
    /** Valeur sentinelle : index hors borne ou données non décodables. */
    private static final int FILE_UNKNOWN      = -1;

    private static final ConcurrentHashMap<Path, TableFile> FILE_CACHE = new ConcurrentHashMap<>();

    private SyzygyRtbwParser() {}

    // =========================================================================
    // API publique
    // =========================================================================

    /**
     * Lit la valeur WDL pour un index de position dans un fichier .rtbw.
     *
     * @param rtbwPath    chemin vers le fichier .rtbw
     * @param index       index calculé par {@link SyzygyPieceLayout#computeIndex}
     * @param stmIsStrong true si le camp à jouer (STM) est le côté fort du fichier
     * @return WDL connu, ou {@link WDL#unknown()} en cas d'erreur
     */
    public static WDL readWDL(Path rtbwPath, long index, boolean stmIsStrong) {
        if (index < 0) return WDL.unknown();
        try {
            TableFile tf = getOrOpen(rtbwPath);
            if (tf == null) return WDL.unknown();
            // Bloc 0 = STM est le côté fort, Bloc 1 = STM est le côté faible
            int raw = readNibble(tf, stmIsStrong ? 0 : 1, index);
            return convertFileValue(raw);
        } catch (Exception e) {
            LOG.warning("Erreur lecture WDL dans " + rtbwPath.getFileName()
                        + " idx=" + index + " : " + e.getMessage());
            return WDL.unknown();
        }
    }

    /** Vide le cache des fichiers mappés en mémoire. */
    public static void clearCache() {
        FILE_CACHE.clear();
    }

    // =========================================================================
    // Cache et ouverture de fichier
    // =========================================================================

    private static TableFile getOrOpen(Path path) {
        return FILE_CACHE.computeIfAbsent(path, p -> {
            try { return openFile(p); }
            catch (IOException e) {
                LOG.warning("Impossible d'ouvrir " + p.getFileName() + " : " + e.getMessage());
                return null;
            }
        });
    }

    /**
     * Ouvre et mappe un fichier .rtbw, puis détecte son format.
     *
     * <h3>Format réel des fichiers Syzygy WDL (.rtbw)</h3>
     * <pre>
     * [0-3]  magic  0x5D23E871 (little-endian)
     * [4]    tb_flags : bit0=split (2 sous-tables), bit1=has_pawns
     * [5+]   descripteurs de pièces (variable) puis en-têtes PairsData Huffman
     * </pre>
     *
     * <p>Les fichiers Syzygy utilisent une compression Huffman sur des symboles
     * composés (séquences de nibbles WDL). Le décodage complet requiert de parser
     * les tables sympat/symlen et le sparse index, ce qui représente ~500 lignes
     * de code C porté depuis tbprobe.c (Ronald de Man / Fathom).
     *
     * <p>Cette implémentation lit uniquement le magic et les flags, et marque le
     * fichier comme "compressé" pour que {@link #readNibble} retourne UNKNOWN
     * silencieusement. Les sondes intégrées de {@link SyzygyTablebase#probeBuiltIn}
     * couvrent les finales les plus fréquentes sans fichiers binaires.
     */
    private static TableFile openFile(Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            long fileSize = ch.size();
            if (fileSize < 8) throw new IOException("Fichier trop court : " + fileSize);

            MappedByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            buf.order(ByteOrder.LITTLE_ENDIAN);

            // ── Magic ─────────────────────────────────────────────────────────
            int magic = buf.getInt(0);
            if (magic != MAGIC_WDL)
                throw new IOException(String.format("Magic invalide : 0x%08X", magic));

            // ── Flags globaux [4] ──────────────────────────────────────────────
            // bit0 = split (2 sous-tables : STM fort et STM faible)
            // bit1 = has_pawns
            int tbFlags = buf.get(4) & 0xFF;
            boolean split    = (tbFlags & 1) != 0;
            boolean hasPawns = (tbFlags & 2) != 0;

            LOG.fine(String.format("Ouvert %s : fileSize=%d flags=0x%02X split=%b pawns=%b",
                     path.getFileName(), fileSize, tbFlags, split, hasPawns));

            // Le vrai décodage Huffman n'est pas encore implémenté.
            // On retourne un TableFile marqué comme « compressé Huffman » avec
            // des offsets/blockSize symboliques ; readNibble retournera FILE_UNKNOWN.
            // Les sondes intégrées (probeBuiltIn) prennent le relais.
            return new TableFile(buf, 0L, 0L, 0L, fileSize);
        }
    }

    // =========================================================================
    // Lecture d'un nibble
    // =========================================================================

    /**
     * Tente de lire la valeur WDL depuis le fichier mappé.
     *
     * <p>Les fichiers Syzygy 3-4-5 utilisent une compression Huffman sur des symboles
     * composés. Cette implémentation retourne {@link #FILE_UNKNOWN} tant que le
     * décodeur Huffman complet n'est pas porté depuis tbprobe.c.
     * Les sondes intégrées de {@link SyzygyTablebase#probeBuiltIn} couvrent les
     * finales les plus fréquentes en attendant.
     */
    private static int readNibble(TableFile tf, int blockIdx, long index) {
        // Décodage Huffman Syzygy non encore implémenté.
        // Retour silencieux de FILE_UNKNOWN → probeBuiltIn prend le relais.
        return FILE_UNKNOWN;
    }

    // =========================================================================
    // Conversion
    // =========================================================================

    private static WDL convertFileValue(int v) {
        return switch (v) {
            case FILE_LOSS         -> WDL.loss();
            case FILE_BLESSED_LOSS -> WDL.blessedLoss();
            case FILE_DRAW         -> WDL.draw();
            case FILE_CURSED_WIN   -> WDL.cursedWin();
            case FILE_WIN          -> WDL.win();
            case FILE_UNKNOWN      -> WDL.unknown();
            default                -> WDL.unknown();
        };
    }

    // =========================================================================
    // Structure interne
    // =========================================================================

    static final class TableFile {
        final MappedByteBuffer buf;
        final long offset0;     // offset du bloc STM=fort
        final long offset1;     // offset du bloc STM=faible
        final long blockSize;   // taille approximative d'un bloc
        final long fileSize;

        TableFile(MappedByteBuffer buf, long offset0, long offset1,
                  long blockSize, long fileSize) {
            this.buf       = buf;
            this.offset0   = offset0;
            this.offset1   = offset1;
            this.blockSize = blockSize;
            this.fileSize  = fileSize;
        }
    }
}
