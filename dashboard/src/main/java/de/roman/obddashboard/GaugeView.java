package de.roman.obddashboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

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
    private final String title;
    private final String unit;
    private final int decimals;
    private double value = Double.NaN;

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
                + (state.isEmpty() ? "" : ", " + state)));
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
        small.setTextSize(Math.max(12f, size * 0.075f));
        canvas.drawText(unit, cx, cy + size * 0.18f, small);
        small.setTextSize(Math.max(12f, size * 0.07f));
        canvas.drawText(title.toUpperCase(java.util.Locale.GERMANY), cx, cy + r + stroke * 0.2f, small);
        String state = stateText();
        if (!state.isEmpty()) {
            small.setColor(Double.isNaN(value) ? Palette.MUTED : Palette.zoneColor(scale.zone(value)));
            canvas.drawText(state, cx, cy - size * 0.16f, small);
            small.setColor(Palette.MUTED);
        }
    }
}
