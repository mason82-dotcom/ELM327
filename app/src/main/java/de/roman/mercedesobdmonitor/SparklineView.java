package de.roman.mercedesobdmonitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayDeque;
import java.util.Deque;

public final class SparklineView extends View {
    private final Deque<Float> values = new ArrayDeque<>();
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int maxPoints = 120;

    public SparklineView(Context context) { super(context); init(); }
    public SparklineView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        linePaint.setColor(Color.rgb(79, 195, 247));
        linePaint.setStrokeWidth(dp(2));
        linePaint.setStyle(Paint.Style.STROKE);
        gridPaint.setColor(Color.rgb(55, 60, 68));
        gridPaint.setStrokeWidth(dp(1));
        textPaint.setColor(Color.LTGRAY);
        textPaint.setTextSize(dp(12));
        setMinimumHeight(dp(120));
    }

    public void addValue(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return;
        if (values.size() >= maxPoints) values.removeFirst();
        values.addLast(value);
        invalidate();
    }

    public void clear() {
        values.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float top = dp(18), bottom = h - dp(12), left = dp(8), right = w - dp(8);
        for (int i = 0; i <= 3; i++) {
            float y = top + (bottom - top) * i / 3f;
            canvas.drawLine(left, y, right, y, gridPaint);
        }
        if (values.isEmpty()) {
            canvas.drawText("Antwortzeit: noch keine Messwerte", left, dp(14), textPaint);
            return;
        }
        float max = 50f;
        for (float v : values) max = Math.max(max, v);
        canvas.drawText("ELM-Antwortzeit (ms), Skala 0–" + Math.round(max), left, dp(14), textPaint);
        if (values.size() == 1) return;
        float dx = (right - left) / (values.size() - 1f);
        float prevX = left;
        Float first = values.peekFirst();
        float prevY = bottom - (first == null ? 0f : first / max) * (bottom - top);
        int i = 0;
        for (float v : values) {
            float x = left + dx * i;
            float y = bottom - (v / max) * (bottom - top);
            if (i > 0) canvas.drawLine(prevX, prevY, x, y, linePaint);
            prevX = x;
            prevY = y;
            i++;
        }
    }

    private float dp(float dp) { return dp * getResources().getDisplayMetrics().density; }
}
