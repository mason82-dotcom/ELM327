package de.roman.obddashboard;

/** Farben des OpenNextion-Displays (ObdDisplay.ino), für Kontrast im Fahrzeug leicht angepasst. */
final class Palette {
    static final int BG = 0xFF0D1523;
    static final int CARD = 0xFF152338;
    static final int BORDER = 0xFF344A66;
    static final int GRID = 0xFF2E425D;
    static final int CYAN = 0xFF70C8FF;
    static final int TEAL = 0xFF53D7CF;
    static final int LIGHT = 0xFFECF4FF;
    static final int MUTED = 0xFFA1B8D2;
    static final int BUTTON = 0xFF225889;
    static final int TAB = 0xFF17283D;
    static final int WARN = 0xFFFFCC76;
    static final int ALARM = 0xFFFF6B6B;
    static final int OK = 0xFF81C784;

    private Palette() { }

    static int zoneColor(GaugeScale.Zone zone) {
        switch (zone) {
            case ALARM: return ALARM;
            case WARN: return WARN;
            default: return CYAN;
        }
    }
}
