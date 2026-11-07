package de.roman.obddashboard;

/**
 * Ringpuffer für Verlaufskurven (Android-frei, testbar). Ungültige Messungen werden als Lücke
 * ({@code NaN}) gespeichert, damit die Kurve nie über einen Ausfall hinweg weiterläuft.
 */
public final class TrendBuffer {
    private final double[] values;
    private int start;
    private int size;

    public TrendBuffer(int capacity) {
        if (capacity < 2) throw new IllegalArgumentException("capacity < 2");
        values = new double[capacity];
    }

    public void add(double value) {
        int index = (start + size) % values.length;
        values[index] = value;
        if (size < values.length) size++;
        else start = (start + 1) % values.length;
    }

    /** Lücke (Messwert ungültig, Verbindung weg). Mehrere Lücken in Folge werden zusammengefasst. */
    public void addGap() {
        if (size > 0 && Double.isNaN(get(size - 1))) return;
        add(Double.NaN);
    }

    public void clear() {
        start = 0;
        size = 0;
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return values.length;
    }

    /** Index 0 = ältester Wert. */
    public double get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(String.valueOf(index));
        return values[(start + index) % values.length];
    }

    /** Neuester gültiger Wert oder NaN. */
    public double latestValid() {
        for (int i = size - 1; i >= 0; i--) {
            double v = get(i);
            if (!Double.isNaN(v)) return v;
        }
        return Double.NaN;
    }
}
