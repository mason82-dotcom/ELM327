package de.roman.mercedesobdmonitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * Gemeinsame Android-Service-Labor-UX beider Apps.
 * Kein Servicebefehl wird durch Öffnen dieses Menüs, durch Autostart oder
 * durch die reine Wahl einer Komfortfunktion gesendet.
 */
public final class ServiceLabDialogs {
    private ServiceLabDialogs() { }

    private static AlertDialog.Builder dialog(Activity a, int theme) {
        return theme == 0 ? new AlertDialog.Builder(a) : new AlertDialog.Builder(a, theme);
    }

    public static void showMenu(Activity a, int theme, ObdSession session) {
        String armed = session.isServiceLabArmed() ? "AKTIV – Einmalfreigabe" : "AUS";
        String[] items = {
                "Service-Modus für diese Verbindung freigeben (" + armed + ")",
                "Mode 04 – emissionsbezogene Fehlercodes löschen",
                "Schiebedach öffnen/schließen – nur Simulation",
                "Codierung, EEPROM, Stellglieder – Forschungsstand"
        };
        dialog(a, theme).setTitle("SERVICE-LABOR · EXPERIMENTELL")
                .setMessage("Nicht die Standard-Read-only-App. Nur Mode 04 kann Fahrzeugdaten verändern. "
                        + "Komfort, Codierung und EEPROM bleiben ohne Fahrzeugansteuerung.")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0 -> confirmArm(a, theme, session);
                        case 1 -> confirmMode04(a, theme, session);
                        case 2 -> showRoofSimulation(a, theme, session);
                        case 3 -> showResearch(a, theme);
                        default -> { }
                    }
                })
                .setNegativeButton("Schließen", null).show();
    }

    private static void confirmArm(Activity a, int theme, ObdSession session) {
        if (session.isServiceLabArmed()) {
            session.armServiceLab(false);
            dialog(a, theme).setTitle("Service-Modus ausgeschaltet")
                    .setMessage("Die einmalige Freigabe wurde zurückgenommen.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        dialog(a, theme).setTitle("Service-Labor freigeben?")
                .setMessage("Nur für die aktuelle, manuell verbundene Sitzung. "
                        + "Es wird jetzt noch kein Fahrzeugbefehl gesendet. "
                        + "Ein späterer Mode-04-Versuch verlangt zusätzlich eine zweistufige Bestätigung "
                        + "und frische Nachweise für Motor AUS und Stillstand.")
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Für diese Verbindung aktivieren", (d, w) -> {
                    session.armServiceLab(true);
                    if (!session.isServiceLabArmed())
                        dialog(a, theme).setTitle("Nicht freigegeben")
                                .setMessage("Adapter zuerst manuell verbinden.")
                                .setPositiveButton("OK", null).show();
                }).show();
    }

    private static void confirmMode04(Activity a, int theme, ObdSession session) {
        if (!session.isServiceLabArmed()) {
            dialog(a, theme).setTitle("Mode 04 gesperrt")
                    .setMessage("Zuerst Service-Modus bewusst freigeben und die Modes 03/07/0A "
                            + "vollständig lesen. Keine Löschung erfolgt.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        dialog(a, theme).setTitle("MODE 04 · UNWIDERRUFLICH")
                .setMessage("Löscht emissionsbezogene Diagnoseinformationen einschließlich "
                        + "gespeicherter DTCs und Freeze-Frames, setzt Readiness zurück und kann "
                        + "die MIL ausschalten. Permanente Mode-0A-Codes bleiben erhalten. "
                        + "Die Informationen vorher sichern!\n\n"
                        + "Nur bei stehendem Fahrzeug, Zündung an, Motor AUS. "
                        + "Das Ergebnis kann bei Timeout UNKLAR sein; niemals automatisch erneut senden. "
                        + "Das Löschen behebt keine Ursache.")
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Weiter zur Bestätigung", (d, w) -> finalConfirmation(a, theme, session))
                .show();
    }

    private static void finalConfirmation(Activity a, int theme, ObdSession session) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (22 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, pad / 2);
        EditText typed = new EditText(a);
        typed.setSingleLine(true);
        typed.setHint("LÖSCHEN eingeben");
        typed.setContentDescription("Zur endgültigen Freigabe das Wort LÖSCHEN eingeben");
        box.addView(typed);
        AlertDialog dlg = dialog(a, theme)
                .setTitle("Endgültige Einzelfreigabe")
                .setMessage("Ich bestätige: eigenes Fahrzeug, Stillstand, Motor aus, "
                        + "Diagnosedaten gesichert. Zum einmaligen Senden von Mode 04 exakt LÖSCHEN eingeben.")
                .setView(box)
                .setNegativeButton("Nicht löschen", null)
                .setPositiveButton("MODE 04 EINMAL SENDEN", (d, w) -> session.clearEmissionCodesConfirmed())
                .create();
        dlg.setOnShowListener(d -> {
            android.widget.Button send = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            send.setEnabled(false);
            typed.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    send.setEnabled("LÖSCHEN".contentEquals(s));
                }
                @Override public void afterTextChanged(Editable e) { }
            });
        });
        dlg.show();
    }

    private static void showRoofSimulation(Activity a, int theme, ObdSession session) {
        String[] actions = {
                "ÖFFNEN (SIMULATION)", "SCHLIESSEN (SIMULATION)",
                "KIPPEN (SIMULATION)", "STOPP (SIMULATION)", "Hindernis simulieren / entfernen"
        };
        dialog(a, theme).setTitle("Schiebedach – NUR SIMULATION")
                .setMessage("Keine echte Bewegung. Dachsteuerung N70/A98 und Eingriffsschutz "
                        + "sind für dieses Fahrzeug noch nicht verifiziert.")
                .setItems(actions, (d, which) -> {
                    String result;
                    if (which == 4) result = session.toggleRoofLabObstacle();
                    else result = session.simulateRoof(RoofLab.Action.values()[which]);
                    dialog(a, theme).setTitle("Offline-Schiebedachmodell")
                            .setMessage(result + "\n\nKEIN Befehl an ELM327 oder Fahrzeug.")
                            .setPositiveButton("OK", null).show();
                })
                .setNegativeButton("Zurück", null).show();
    }

    private static void showResearch(Activity a, int theme) {
        dialog(a, theme).setTitle("Codierung / EEPROM / Stellglieder")
                .setMessage("W204-Referenzen: DBE/N70 (Dachbedienung), A98 (Panoramadach), "
                        + "IC_204 (Kombiinstrument), SAM vorn/hinten und VGS/7G-Tronic.\n\n"
                        + "Variante, Steuergerätekennung, Softwarestand, Ausstattungs-Code "
                        + "413/414 und Rücksicherung müssen zuerst bestätigt werden. "
                        + "Für Codierung/EEPROM gibt es keine universelle sichere "
                        + "ELM327-Schreibadresse.\n\n"
                        + "Keine Schlüssel-/Wegfahrsperren-, Kilometerstands-, Airbag-, "
                        + "ESP- oder Abgasmanipulation. Keiner dieser Bereiche ist als Fahrzeugbefehl implementiert.")
                .setPositiveButton("Verstanden", null).show();
    }
}
