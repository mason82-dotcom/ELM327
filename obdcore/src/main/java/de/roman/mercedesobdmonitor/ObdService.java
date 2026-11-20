package de.roman.mercedesobdmonitor;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Foreground-Service für die ELM327-Verbindung.
 *
 * Hält den Prozess für die {@link ObdSession} im Vordergrund, damit Live-Polling,
 * Reconnect und CSV-Log auch bei ausgeschaltetem Bildschirm und mit der App im
 * Hintergrund weiterlaufen. Der Service läuft genau so lange, wie die Sitzung aktiv
 * ist (verbunden oder im Reconnect), und zeigt deren Status in der Benachrichtigung.
 */
public final class ObdService extends Service {
    public static final String ACTION_START = "de.roman.mercedesobdmonitor.action.START";
    public static final String ACTION_DISCONNECT = "de.roman.mercedesobdmonitor.action.DISCONNECT";
    /** ADR-006: nach dem Radiostart verbinden, bis es klappt (höchstens {@link #AUTO_WINDOW_MS}). */
    public static final String ACTION_AUTO_CONNECT = "de.roman.mercedesobdmonitor.action.AUTO_CONNECT";
    /** Name der App-Einstellungen mit Adresse, Port und Adapterwahl. */
    public static final String EXTRA_PREFS = "prefs";
    static final long AUTO_RETRY_MS = 20_000;
    static final long AUTO_WINDOW_MS = 10 * 60 * 1000L;

    private static final String CHANNEL_ID = "obd_connection";
    private static final int NOTIFICATION_ID = 1;
    /** Statistik-Updates der Benachrichtigung höchstens alle 2 s; Statuswechsel sofort. */
    private static final long NOTIFY_INTERVAL_MS = 2000;
    /** Obergrenze, falls das Freigeben einmal ausbleibt; wird bei jedem Start neu gesetzt. */
    private static final long WAKE_LOCK_TIMEOUT_MS = 8 * 60 * 60 * 1000L;

    private ObdSession session;
    private PowerManager.WakeLock wakeLock;
    private boolean foreground;
    private String lastStatus = "";
    private String lastStats = "";
    private long lastNotifyAt;
    /** Einstellungen fürs automatische Verbinden; null = kein Auto-Verbinden offen. */
    private String autoPrefs;
    private long autoDeadline;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable autoAttempt = this::autoAttempt;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        session = ObdSession.get();
        session.setObserver(this::onSessionChanged);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            // Auto-Verbinden zuerst beenden, sonst hielte es den Vordergrund bis zum nächsten Versuch (Review)
            stopAutoConnect();
            // Der Observer beendet danach den Vordergrundbetrieb.
            session.disconnect();
            if (!foreground) stopSelf();
        } else if (ACTION_AUTO_CONNECT.equals(action)) {
            // startForeground() muss nach startForegroundService() immer kommen, auch ohne gültigen Auftrag (Review)
            if (!enterForeground()) return START_NOT_STICKY;
            String prefs = intent.getStringExtra(EXTRA_PREFS);
            if (prefs == null) {
                if (!session.isActive()) leaveForeground();
                return START_NOT_STICKY;
            }
            autoPrefs = prefs;
            autoDeadline = SystemClock.elapsedRealtime() + AUTO_WINDOW_MS;
            handler.removeCallbacks(autoAttempt);
            handler.post(autoAttempt);
        } else if (ACTION_START.equals(action)) {
            // Nach startForegroundService() muss startForeground() immer aufgerufen werden,
            // auch wenn die Verbindung inzwischen schon wieder gescheitert ist.
            if (enterForeground() && !session.isActive()) leaveForeground();
        }
        return START_NOT_STICKY;
    }

    /**
     * Ein Versuch des automatischen Verbindens. Steht die Sitzung, übernimmt deren eigenes Wiederverbinden; nach
     * „Trennen“ oder Ablauf des Fensters endet das Auto-Verbinden.
     */
    private void autoAttempt() {
        String prefs = autoPrefs;
        if (prefs == null) return;
        // Einstellung wird bei jedem Versuch neu gelesen: Abschalten unter INFO beendet ein laufendes Fenster (Review)
        if (session.wasUserDisconnect() || SystemClock.elapsedRealtime() > autoDeadline
                || !AdapterChoice.autoConnect(this, prefs)) {
            stopAutoConnect();
            if (!session.isActive()) leaveForeground();
            return;
        }
        if (session.isActive()) {
            // Steht die Sitzung, hält sie die Verbindung selbst (Wiederverbinden) – kein weiteres Abfragen (Review)
            stopAutoConnect();
            return;
        }
        if (AdapterChoice.connectFromSettings(this, session, prefs) == AdapterChoice.AutoResult.GIVE_UP) {
            stopAutoConnect();
            leaveForeground();
            return;
        }
        handler.postDelayed(autoAttempt, AUTO_RETRY_MS);   // gestartet oder vorübergehend nicht möglich
    }

    private void stopAutoConnect() {
        autoPrefs = null;
        handler.removeCallbacks(autoAttempt);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(autoAttempt);
        releaseWakeLock();
        session.setObserver(null);
        super.onDestroy();
    }

    private void onSessionChanged(boolean active, String status, String stats) {
        if (!active) {
            // Beim Auto-Verbinden bleibt der Dienst bis zum nächsten Versuch im Vordergrund (ADR-006)
            if (foreground && autoPrefs == null) leaveForeground();
            return;
        }
        boolean statusChanged = !status.equals(lastStatus);
        lastStatus = status;
        lastStats = stats;
        if (!foreground) return;
        long now = SystemClock.elapsedRealtime();
        if (!statusChanged && now - lastNotifyAt < NOTIFY_INTERVAL_MS) return;
        lastNotifyAt = now;
        if (!canPostNotifications()) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
    }

    /** @return false, wenn Android den Vordergrundstart verweigert hat (Sitzung wird beendet) */
    private boolean enterForeground() {
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (RuntimeException e) {
            // z. B. ForegroundServiceStartNotAllowedException oder fehlende Typ-Berechtigung. Nur eine laufende Sitzung
            // trennen: beim Auto-Verbinden gäbe es keine, und disconnect() würde als „vom Benutzer getrennt“ zählen
            // und das spätere automatische Verbinden beim App-Start verhindern (Review).
            if (session.isActive()) session.disconnect("Hintergrunddienst konnte nicht gestartet werden: " + e.getMessage());
            stopSelf();
            return false;
        }
        foreground = true;
        lastNotifyAt = SystemClock.elapsedRealtime();
        acquireWakeLock();
        return true;
    }

    private void leaveForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        releaseWakeLock();
        stopSelf();
    }

    private Notification buildNotification() {
        String status = lastStatus.isEmpty() ? "Verbinde …" : lastStatus;
        // Startbildschirm der jeweiligen App (:app oder :dashboard); der Dienst kennt keine Activity.
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        Intent open = (launch != null ? launch : new Intent())
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopIntent = PendingIntent.getService(this, 1,
                new Intent(this, ObdService.class).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(de.roman.mercedesobdmonitor.core.R.drawable.ic_launcher_monochrome)
                .setContentTitle("OBD2-Verbindung aktiv")
                .setContentText(status)
                .setStyle(new Notification.BigTextStyle()
                        .bigText(lastStats.isEmpty() ? status : status + "\n" + lastStats))
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "Trennen", stopIntent).build());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "ELM327-Verbindung",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Laufende OBD2-Verbindung mit Live-Polling und Log");
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private boolean canPostNotifications() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Hält die CPU bei ausgeschaltetem Bildschirm wach, damit das Polling weiterläuft. */
    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MercedesOBD2Monitor:connection");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
}
