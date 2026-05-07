package engine.book;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lecteur de fichiers de livres d'ouvertures au format Polyglot (.bin).
 *
 * <h2>Format Polyglot</h2>
 * Chaque entrée fait 16 octets, big-endian :
 * <pre>
 *   bytes 0-7  : Zobrist key (uint64)
 *   bytes 8-9  : coup encodé (uint16)
 *   bytes 10-11: weight (uint16) — fréquence / score
 *   bytes 12-15: learn (uint32) — ignoré
 * </pre>
 *
 * <h2>Encodage du coup Polyglot (16 bits)</h2>
 * <pre>
 *   bits 0-5   : case d'arrivée (to_file + to_rank*8)
 *   bits 6-11  : case de départ (from_file + from_rank*8)
 *   bits 12-14 : pièce de promotion (0=aucune,1=cavalier,2=fou,3=tour,4=dame)
 * </pre>
 * Attention : le champ "to" occupe les bits bas et "from" les bits hauts —
 * ordre INVERSE de la convention UCI interne du moteur.
 *
 * <h2>Clé Zobrist Polyglot</h2>
 * Polyglot utilise sa propre table Zobrist (781 valeurs) différente de celle
 * du moteur. La conversion est faite dans {@link OpeningBook}.
 *
 * <p>Le fichier est chargé entièrement en mémoire au démarrage (1–30 Mo).</p>
 * Les entrées étant triées par clé dans le fichier, la recherche utilise
 * une dichotomie O(log n).
 */
public final class PolyglotReader {

    /** Taille d'une entrée Polyglot en octets. */
    private static final int ENTRY_SIZE = 16;

    /**
     * Données compactées en triplets : [i*3]=key, [i*3+1]=move16, [i*3+2]=weight.
     * Évite de créer N objets PolyglotEntry et réduit la pression GC.
     */
    private final long[] data;
    private final int    count;

    // ── Construction ──────────────────────────────────────────────────────────

    /**
     * Charge un fichier Polyglot (.bin) depuis le chemin donné.
     *
     * @param path chemin vers le fichier .bin
     * @throws IOException si le fichier ne peut pas être lu
     */
    public PolyglotReader(Path path) throws IOException {
        byte[] raw = Files.readAllBytes(path);
        int n = raw.length / ENTRY_SIZE;
        this.count = n;
        this.data  = new long[n * 3];

        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < n; i++) {
            long key    = buf.getLong();
            int  move16 = buf.getShort() & 0xFFFF;
            int  weight = buf.getShort() & 0xFFFF;
            buf.getInt(); // learn — ignoré
            data[i * 3]     = key;
            data[i * 3 + 1] = move16;
            data[i * 3 + 2] = weight;
        }
    }

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Retourne toutes les entrées (coup, poids) pour la clé Polyglot donnée.
     * La liste est vide si la position n'est pas dans le livre.
     */
    public List<PolyglotEntry> getEntries(long polyglotKey) {
        int lo = lowerBound(polyglotKey);
        if (lo >= count || data[lo * 3] != polyglotKey) return Collections.emptyList();

        List<PolyglotEntry> result = new ArrayList<>(4);
        for (int i = lo; i < count && data[i * 3] == polyglotKey; i++) {
            result.add(new PolyglotEntry((int) data[i * 3 + 1], (int) data[i * 3 + 2]));
        }
        return result;
    }

    /** Nombre total d'entrées dans le fichier. */
    public int getCount() { return count; }

    // ── Recherche binaire ─────────────────────────────────────────────────────

    /** Borne inférieure : premier index dont la clé >= target (clés non-signées). */
    private int lowerBound(long target) {
        int lo = 0, hi = count;
        while (lo < hi) {
            int  mid = (lo + hi) >>> 1;
            long key = data[mid * 3];
            if (Long.compareUnsigned(key, target) < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    // ── Record résultat ───────────────────────────────────────────────────────

    /**
     * Entrée brute Polyglot : coup 16-bit + poids.
     * La conversion en {@code Move} est faite par {@link OpeningBook}.
     */
    public record PolyglotEntry(int move16, int weight) {}
}
