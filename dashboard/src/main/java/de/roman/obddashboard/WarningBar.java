package de.roman.obddashboard;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import de.roman.mercedesobdmonitor.LiveWarnings;

/**
 * Warnleiste unter der Kopfzeile, über allen Seiten; unsichtbar, solange alles in Ordnung ist. Nur Anzeige:
 * Regeln in {@link LiveWarnings}, Ton und „Ton aus“ in der Sitzung (laufen auch im Hintergrund weiter).
 */
final class WarningBar {
    private final LinearLayout bar;
    private final TextView text;
    private final Button mute;
    private final GradientDrawable warnBg = solid(Palette.WARN);
    private final GradientDrawable alarmBg = solid(Palette.ALARM);

    /** @param mute fertig gestalteter Knopf „Ton aus“ (Stil der Activity) */
    WarningBar(Context context, Button mute, int dp4) {
        this.mute = mute;
        bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp4 * 3, dp4, dp4 * 2, dp4);
        text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextColor(Palette.ON_SIGNAL);
        text.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        bar.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(mute, new LinearLayout.LayoutParams(dp4 * 30, dp4 * 12));
        bar.setVisibility(View.GONE);
    }

    View view() {
        return bar;
    }

    void show(LiveWarnings.Warning w, boolean canMute) {
        if (w == null) {
            bar.setVisibility(View.GONE);
            return;
        }
        boolean alarm = w.level == LiveWarnings.Level.ALARM;
        bar.setBackground(alarm ? alarmBg : warnBg);
        text.setText((alarm ? "ALARM  " : "WARNUNG  ") + w.text);
        mute.setVisibility(canMute ? View.VISIBLE : View.GONE);
        bar.setVisibility(View.VISIBLE);
    }

    private static GradientDrawable solid(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        return d;
    }
}
