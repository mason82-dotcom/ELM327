package de.roman.obddashboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Verlaufskurve wie die GRAPH-Seite des OpenNextion-Displays (dunkles Raster, Linie in Türkis),
 * mit Lücken bei ungültigen Werten statt Interpolation über Ausfälle hinweg.
 */
final class TrendView extends View {
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private TrendBuffer buffer;
    private double min;
    private double max;
    private String unit = "";

    TrendView(Context context) {
        super(context);
        grid.setColor(Palette.GRID);
        grid.setStrokeWidth(1.5f);
        line.setColor(Palette.TEAL);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.ROUND);
        label.setColor(Palette.MUTED);
    }

    void show(TrendBuffer buffer, double min, double max, String unit) {
        this.buffer = buffer;
        this.min = min;
        this.max = max;
        this.unit = unit;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float pad = h * 0.06f;
        float density = getResources().getDisplayMetrics().density;
        label.setTextSize(12f * density);
        line.setStrokeWidth(3f * density);
        float left = label.measureText("00000 ") + pad;
        float top = pad, bottom = h - pad, right = w - pad;
        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            canvas.drawLine(left, y, right, y, grid);
            double v = max - (max - min) * i / 4.0;
            canvas.drawText(String.format(java.util.Locale.GERMANY, "%.0f", v), pad * 0.5f, y + label.getTextSize() * 0.35f, label);
        }
        canvas.drawText(unit, pad * 0.5f, bottom + pad * 0.9f, label);
        if (buffer == null || buffer.size() < 2) return;
        int cap = buffer.capacity(), n = buffer.size();
        path.reset();
        boolean pen = false;
        for (int i = 0; i < n; i++) {
            double v = buffer.get(i);
            if (Double.isNaN(v)) {
                pen = false;
                continue;
            }
            float x = right - (right - left) * (n - 1 - i) / (float) (cap - 1);
            double f = (Math.max(min, Math.min(max, v)) - min) / (max - min);
            float y = (float) (bottom - (bottom - top) * f);
            if (pen) path.lineTo(x, y);
            else path.moveTo(x, y);
            pen = true;
        }
        canvas.drawPath(path, line);
    }
}
