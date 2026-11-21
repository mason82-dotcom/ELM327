package de.roman.obddashboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import de.roman.mercedesobdmonitor.ObdSession;

/**
 * Rundinstrument (270°-Bogen) mit großer Ziffernanzeige. Ohne gültigen Wert: „–“, kein Zeiger,
 * gedimmter Bogen – ein alter Wert wird nie stehen gelassen. Farbe zeigt Warn-/Alarmbereich,
 * zusätzlich steht der Zustand als Text darunter (nie nur Farbe).
 */
@SuppressLint("ViewConstructor") // nur im Code erzeugt, nie aus XML
final class GaugeView extends View {
    private static final float SWEEP = 270f;
    private static final float START = 135f;

    private final GaugeScale scale;

    private final float minSmall;
    private final String title;
    private final String unit;
    private final int decimals;
    private double value = Double.NaN;
    /** Kurztext oben im Bogen (Gang am Drehzahlmesser, ADR-008); null = nicht angezeigt. */
    private String badge;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zone = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final RectF outer = new RectF();

    GaugeView(Context context, String title, String unit, int decimals, GaugeScale scale) {
        super(context);
        this.title = title;
        this.unit = unit;
        this.decimals = decimals;
        this.scale = scale;
        // Mindestgröße 12 sp (vorher 12 px ≈ 4,6 sp auf 420 dpi – Einheit/Titel im Querformat kaum lesbar)
        minSmall = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 12f,
                context.getResources().getDisplayMetrics());
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(Palette.GRID);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        zone.setStyle(Paint.Style.STROKE);
        big.setColor(Palette.LIGHT);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        small.setColor(Palette.MUTED);
        small.setTextAlign(Paint.Align.CENTER);
        updateDescription();
    }

    void setValue(double v) {
        value = v;
        updateDescription();
        invalidate();
    }

    void setInvalid() {
        setValue(Double.NaN);
    }

    /** Gang wie im Kombiinstrument neben der Drehzahl: „D 3“, „P“ … oder „–“ ohne frischen Wert (nicht gezeichnet). */
    void setBadge(String text) {
        if (java.util.Objects.equals(badge, text)) return;
        badge = text;
        updateDescription();
        invalidate();
    }

    private String valueText() {
        if (Double.isNaN(value)) return "–";
        return String.format(java.util.Locale.GERMANY, "%." + decimals + "f", value);
    }

    private String stateText() {
        if (Double.isNaN(value)) return "kein Messwert";
        switch (scale.zone(value)) {
            case ALARM: return "Alarmbereich";
            case WARN: return "Warnbereich";
            default: return "";
        }
    }

    private void updateDescription() {
        String state = stateText();
        setContentDescription(title + ": " + (Double.isNaN(value) ? "kein Messwert" : valueText() + " " + unit
                + (state.isEmpty() ? "" : ", " + state))
                + (badge == null ? "" : ", Gang " + (ObdSession.INVALID_TEXT.equals(badge) ? "unbekannt" : badge)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float size = Math.min(w, h * 1.12f);
        float stroke = Math.max(6f, size * 0.07f);
        float r = size / 2f - stroke;
        float cx = w / 2f, cy = h / 2f + size * 0.04f;
        oval.set(cx - r, cy - r, cx + r, cy + r);
        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);
        zone.setStrokeWidth(stroke * 0.35f);

        canvas.drawArc(oval, START, SWEEP, false, track);
        // Warn-/Alarmbereich als dünner Außenring, damit er ohne Messwert sichtbar bleibt.
        outer.set(oval);
        outer.inset(-stroke * 0.85f, -stroke * 0.85f);
        if (!Double.isNaN(scale.warnFrom)) {
            zone.setColor(Palette.WARN);
            float from = (float) scale.fraction(scale.warnFrom);
            float to = Double.isNaN(scale.alarmFrom) ? 1f : (float) scale.fraction(scale.alarmFrom);
            canvas.drawArc(outer, START + SWEEP * from, SWEEP * (to - from), false, zone);
        }
        if (!Double.isNaN(scale.alarmFrom)) {
            zone.setColor(Palette.ALARM);
            float from = (float) scale.fraction(scale.alarmFrom);
            canvas.drawArc(outer, START + SWEEP * from, SWEEP * (1f - from), false, zone);
        }
        if (!Double.isNaN(value)) {
            arc.setColor(Palette.zoneColor(scale.zone(value)));
            canvas.drawArc(oval, START, Math.max(1f, SWEEP * (float) scale.fraction(value)), false, arc);
        }

        big.setTextSize(size * 0.22f);
        big.setColor(Double.isNaN(value) ? Palette.MUTED : Palette.LIGHT);
        canvas.drawText(valueText(), cx, cy + size * 0.07f, big);
        small.setTextSize(Math.max(minSmall, size * 0.075f));
        canvas.drawText(unit, cx, cy + size * 0.18f, small);
        // Titel in der unteren Öffnung des Bogens: liegt immer innerhalb der View (vorher unten abgeschnitten).
        small.setTextSize(Math.max(minSmall, size * 0.068f));
        small.setColor(Palette.MUTED);
        String caps = title.toUpperCase(java.util.Locale.GERMANY);
        // Die Öffnung des Bogens ist auf Titelhöhe etwa 1,1 r breit: kleine Instrumente (Querformat) verkleinern
        // den Titel, statt ihn über den Bogen laufen zu lassen („KÜHLMITTEL“).
        float room = r * 1.1f, width = small.measureText(caps);
        if (width > room) small.setTextSize(small.getTextSize() * room / width);
        canvas.drawText(caps, cx, Math.min(cy + r * 0.78f, h - small.descent()), small);
        if (badge != null && !ObdSession.INVALID_TEXT.equals(badge)) {
            // Oben im Bogen, über dem Zustandstext, groß genug für einen kurzen Blick. Ohne frischen Gang nichts
            // zeichnen: ein zweites „–“ über dem Drehzahl-„–“ wirkte unruhig; der Vorlesetext nennt „Gang unbekannt“.
            big.setTextSize(size * 0.12f);
            big.setColor(Palette.CYAN);
            canvas.drawText(badge, cx, cy - size * 0.24f, big);
        }
        // Zustand nur bei Warn-/Alarmbereich; ohne Messwert genügt „–“ (Vorlesetext nennt „kein Messwert“).
        if (!Double.isNaN(value) && scale.zone(value) != GaugeScale.Zone.NORMAL) {
            small.setColor(Palette.zoneColor(scale.zone(value)));
            small.setTextSize(Math.max(minSmall, size * 0.068f));   // eigene Größe, nicht die eingepasste des Titels
            canvas.drawText(stateText(), cx, cy - size * 0.16f, small);
            small.setColor(Palette.MUTED);
        }
    }
}
