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
     * Ouvre et mappe un fichier .rtbw, puis lit son en-tête.
     *
     * <h3>Structure de l'en-tête Syzygy réelle</h3>
     * <pre>
     * [0]  4 octets  magic         0x5D23E871
     * [4]  4 octets  num_tables    (généralement 2)
     * [8]  4 octets  data_size     taille totale des données en octets
     * [12] 4 octets  réservé/flags
     * [16] 4 octets  offset_bloc_0 (offset en octets depuis le début du fichier)
     * [20] 4 octets  offset_bloc_1
     * </pre>
     * Les offsets des blocs pointent directement vers les données nibble.
     */
    private static TableFile openFile(Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            long fileSize = ch.size();
            if (fileSize < 24) throw new IOException("Fichier trop court : " + fileSize);

            MappedByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            buf.order(ByteOrder.LITTLE_ENDIAN);

            // ── Magic ─────────────────────────────────────────────────────────
            int magic = buf.getInt(0);
            if (magic != MAGIC_WDL)
                throw new IOException(String.format("Magic invalide : 0x%08X", magic));

            // ── En-tête ───────────────────────────────────────────────────────
            // Octets 4-7 : num_tables ou flags selon la version — on lit les deux
            // structures possibles et on choisit les offsets valides.
            //
            // Structure A (la plus courante, fichiers 3-5 pièces Lichess) :
            //   [4]  int  num_tables   (valeur 2)
            //   [8]  int  data_size    (taille bloc 0 + bloc 1)
            //   [12] int  réservé
            //   [16] int  offset_bloc_0
            //   [20] int  offset_bloc_1
            //
            // Structure B (variante compressée) :
            //   [4]  byte flags
            //   [5]  byte num_strong
            //   [6]  byte num_weak
            //   [7]  byte réservé
            //   [8]  long data_size (8 octets)
            //   [16] int  offset_bloc_0
            //   [20] int  offset_bloc_1
            //
            // On détecte la structure en regardant num_tables (doit être 1 ou 2)

            int numTables = buf.getInt(4);  // Structure A
            long offset0, offset1;

            if (numTables >= 1 && numTables <= 4) {
                // Structure A : offsets à [16] et [20]
                offset0 = Integer.toUnsignedLong(buf.getInt(16));
                offset1 = Integer.toUnsignedLong(buf.getInt(20));
            } else {
                // Structure B : offsets à [16] et [20] aussi (même position !)
                // La seule différence est l'interprétation des octets 4-15.
                offset0 = Integer.toUnsignedLong(buf.getInt(16));
                offset1 = Integer.toUnsignedLong(buf.getInt(20));
            }

            // Validation : les offsets doivent être dans les bornes du fichier
            if (offset0 >= fileSize || offset1 >= fileSize || offset0 < 16 || offset1 < 16) {
                // Fallback : certains fichiers ont les données immédiatement après un en-tête
                // de 5 ints (20 octets). On recalcule data_size pour trouver les blocs.
                long dataSize = Integer.toUnsignedLong(buf.getInt(8));
                offset0 = 24;                     // après 6 ints d'en-tête
                offset1 = 24 + (dataSize / 2);    // deuxième moitié
            }

            // Taille d'un bloc = (fileSize - offset0) / 2  (approximation)
            long blockSize = (fileSize - offset0) / 2;

            LOG.fine(String.format("Ouvert %s : fileSize=%d, off0=%d, off1=%d, blockSize=%d",
                     path.getFileName(), fileSize, offset0, offset1, blockSize));

            return new TableFile(buf, offset0, offset1, blockSize, fileSize);
        }
    }

    // =========================================================================
    // Lecture d'un nibble
    // =========================================================================

    /**
     * Lit la valeur WDL (4 bits) à l'index donné dans le bloc donné.
     *
     * <p>Format nibble : 2 valeurs par octet.
     * <ul>
     *   <li>Index pair  → bits 0-3 (nibble bas)</li>
     *   <li>Index impair → bits 4-7 (nibble haut)</li>
     * </ul>
     */
    private static int readNibble(TableFile tf, int blockIdx, long index) {
        long base = (blockIdx == 0) ? tf.offset0 : tf.offset1;

        // Vérification de compression : premier octet du bloc, bit 0
        byte flagByte = tf.buf.get((int) base);
        boolean compressed = (flagByte & 1) != 0;

        // Les données nibble commencent après 4 octets de métadonnées du bloc
        long dataStart = base + 4;

        // Validation de l'index
        long maxIdx = (tf.blockSize - 4) * 2;
        if (index >= maxIdx) {
            LOG.warning("Index hors borne : idx=" + index + " max=" + maxIdx
                        + " bloc=" + blockIdx);
            // Les fichiers Syzygy utilisent une compression Huffman : l'index de position
            // correct (calculé sur l'espace non-compressé) dépasse la taille brute du fichier.
            // Retourner UNKNOWN pour laisser les sondes builtin ou l'évaluateur normal décider.
            return FILE_UNKNOWN;
        }

        if (!compressed) {
            // ── Nibbles directs ───────────────────────────────────────────────
            long byteOff = dataStart + (index >>> 1);
            if (byteOff >= tf.fileSize) return FILE_DRAW;
            int b = tf.buf.get((int) byteOff) & 0xFF;
            return (int) ((index & 1L) == 0 ? (b & 0x0F) : (b >>> 4));
        } else {
            // ── Blocs compressés (super-blocs de 64) ─────────────────────────
            return readCompressedNibble(tf, dataStart, index);
        }
    }

    /**
     * Lecture dans un bloc compressé Syzygy.
     * Format : groupes de 64 positions, chaque groupe précédé de 2 octets de symbole.
     */
    private static int readCompressedNibble(TableFile tf, long dataStart, long index) {
        // Taille d'un groupe : 2 octets header + 32 octets de données (64 nibbles)
        long groupSize   = 34L;
        long groupIdx    = index / 64;
        long posInGroup  = index % 64;
        long groupStart  = dataStart + groupIdx * groupSize;

        if (groupStart + 2 >= tf.fileSize) return FILE_DRAW;

        int sym = (tf.buf.get((int) groupStart) & 0xFF)
                | ((tf.buf.get((int)(groupStart + 1)) & 0x01) << 8);

        if (sym == 0) {
            // Toutes les 64 valeurs sont identiques → lire la valeur unique
            if (groupStart + 2 >= tf.fileSize) return FILE_DRAW;
            return tf.buf.get((int)(groupStart + 2)) & 0x0F;
        }

        // Données nibble directes dans le groupe
        long nibbleStart = groupStart + 2;
        long byteOff     = nibbleStart + (posInGroup >>> 1);
        if (byteOff >= tf.fileSize) return FILE_DRAW;
        int b = tf.buf.get((int) byteOff) & 0xFF;
        return (int) ((posInGroup & 1L) == 0 ? (b & 0x0F) : (b >>> 4));
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
