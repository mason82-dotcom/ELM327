package de.roman.mercedesobdmonitor;

/** Kleine gemeinsame Hilfen für Sitzung und Diagnoseabläufe (ohne Android). */
final class ElmText {
    private ElmText() { }

    /** Adapterantwort für eine Protokollzeile: Zeilenumbrüche zu Leerzeichen, null als „–“. */
    static String clean(String s) {
        if (s == null) return "–";
        return s.replace('\r', ' ').replace('\n', ' ').trim();
    }

    static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
