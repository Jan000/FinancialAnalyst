# Finanzanalyst

Android-App, die alle eigenen Konten zusammenführt und auswertet: Einnahmen und Ausgaben nach Tag, Woche, Monat,
Quartal und Jahr, Fixkosten und Abos, ein Tagesbudget („Heute noch verfügbar"), Kredite mit Tilgungsplan,
Dispozinsen je Konto, Budgets, Sparziele und eine verschlüsselte Sicherung in Google Drive.

Alle Daten liegen auf dem Gerät. Nach außen gehen nur der Bankabruf (Enable Banking) und die verschlüsselte
Sicherung (Google Drive), beides erst nach ausdrücklicher Einrichtung.

## Bauen und installieren

Voraussetzungen: JDK 17 oder neuer, Android SDK (Plattform 37), `local.properties` mit `sdk.dir=…`.

```
.\gradlew.bat :domain:test :app:testDebugUnitTest   # Tests
.\gradlew.bat :app:assembleDebug                    # APK nach app\build\outputs\apk\debug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Aufbau

| Modul | Inhalt |
|---|---|
| `domain` | Reines Kotlin ohne Android: Geldbeträge, CSV-Import, Dubletten-Abgleich, Kategorisierung, Umbuchungs- und Dauerposten-Erkennung, Auswertung, Budget, Kredite, Dispo, Sicherungsverschlüsselung, Token-Signatur. Vollständig per Unit-Test abgedeckt. |
| `app` | Room-Datenbank, Bankabruf, Benachrichtigungs-Dienst, Sicherung, Compose-Oberfläche. |

Geldbeträge sind durchgehend ganze Cent (`Long`). Der Kontostand eines Kontos ist immer
`Anfangssaldo + Summe der Umsätze`; dadurch lässt sich jeder frühere Tagessaldo (für Dispozinsen und den
Vermögensverlauf) rekonstruieren.

## Woher die Umsätze kommen

1. **Bankabruf** über [Enable Banking](https://enablebanking.com) – derselbe PSD2-Zugang, den auch Apps wie Finanzguru nutzen.
2. **Datei-Import** (CSV) für alles, was dort nicht gelistet ist. DKB, N26, American Express und PayPal werden erkannt,
   andere Dateien über eine Spaltenzuordnung eingelesen. Eine Datei lässt sich auch aus anderen Apps an den Finanzanalysten *teilen*.
3. **Benachrichtigungen** der Banking-Apps als vorläufige Umsätze, die die echte Buchung später ersetzt.
4. **Von Hand.**

Derselbe Umsatz erscheint nie doppelt, egal über welchen Weg er zuerst kam (`Reconciler`).

### Bankabruf einrichten

Für eigene Konten ist Enable Banking laut deren Nutzungsbedingungen kostenlos („restricted mode").

1. Auf <https://enablebanking.com/sign-in/> anmelden, im Control Panel **Add a new application**, Umgebung **Production**.
2. Als Redirect-URL `finanzanalyst://callback` eintragen. Falls das Formular nur `https`-Adressen annimmt, stattdessen
   die Weiterleitungsseite `docs/callback.html` dieses Repos irgendwo per HTTPS bereitstellen (z. B. GitHub Pages) und
   deren Adresse eintragen; die Seite reicht `code` und `state` nur an die App weiter.
3. Die erzeugte Schlüsseldatei `<App-ID>.pem` aufs Handy laden.
4. Im Control Panel **Activate by linking accounts** wählen und jedes Konto verknüpfen, das abgerufen werden soll.
5. In der App: **Mehr → Bankverbindungen**, Schlüsseldatei wählen (die App-ID wird aus dem Dateinamen übernommen),
   dieselbe Rücksprung-Adresse eintragen, dann **Bank verbinden**.

Der private Schlüssel liegt AES-GCM-verschlüsselt auf dem Gerät, der Schlüssel dazu im Android Keystore. Er ist nicht
Teil der Sicherung. Enable Banking rät davon ab, Schlüssel in Apps einzubetten, die an Dritte verteilt werden – diese
App ist für den Eigengebrauch mit dem eigenen Schlüssel gedacht und darf so nicht veröffentlicht werden.

Die Bankfreigabe gilt meist 180 Tage und muss dann erneuert werden; die App weist rechtzeitig darauf hin. Im
Hintergrund wird dreimal täglich abgerufen, weil Banken ohne Nutzerbestätigung meist nur vier Abrufe pro Tag zulassen.
Einzelne Konten lassen sich gezielt abrufen (Bankverbindungen → „Abrufen" am Konto oder im Konto selbst), ohne die
Abruflimits der übrigen Banken anzutasten.

Über die Schnittstelle kommen nur Zahlungskonten (Giro, Kreditkarte, Tagesgeld). Kredite und Depots gibt keine Bank so
heraus; liefert eine Freigabe gar kein Konto (so bei der DKB mit nur einem Kredit), sagt die App das. Kredite trägt man
unter **Kredite** ein; ihre errechnete Restschuld steht dann auch in der Kontenliste.

### Google-Drive-Sicherung einrichten

Die Sicherung ist mit einem Passwort verschlüsselt, das nur du kennst (AES-256-GCM, Schlüssel per PBKDF2), und liegt
im versteckten App-Ordner von Drive (`drive.appdata`).

1. In der [Google Cloud Console](https://console.cloud.google.com/) ein Projekt anlegen und die **Google Drive API** aktivieren.
2. **OAuth-Zustimmungsbildschirm** einrichten (Typ „Extern", eigenes Google-Konto als Testnutzer).
3. **Anmeldedaten → OAuth-Client-ID → Android**: Paketname `net.jandev.financialanalyst` und den SHA-1-Fingerabdruck
   des Signaturschlüssels eintragen. Für Debug-Builds:
   `keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android`
4. In der App: **Mehr → Sicherung**, Passwort festlegen, „Täglich automatisch sichern" einschalten.

Eine Client-ID muss nicht in die App eingetragen werden; Google ordnet sie über Paketname und Fingerabdruck zu.
Ohne Google-Einrichtung funktioniert die Sicherung als Datei (**Datei speichern / Aus Datei laden**).

## Kategorisieren, Regeln, Gruppen

- **Kategorie ändern:** Umsatz lange drücken. Vor der Übernahme auf gleiche Zahlungen zeigt eine Vorschau, welche
  Umsätze betroffen sind; einzelne lassen sich abwählen, die Auswahl lässt sich auf den gleichen Betrag einengen.
- **Regeln** (Mehr → Regeln): eine oder mehrere Bedingungen (Gegenpartei, Verwendungszweck, Betrag, Art, Konto), die
  alle zutreffen müssen. Neue Umsätze werden automatisch zugeordnet; „Speichern und Umsätze zuordnen" wendet eine
  Regel nach Vorschau auf vorhandene Umsätze an. Eigene Regeln gehen den mitgelieferten Schlagwörtern vor.
- **Kategorien** (Mehr → Kategorien, oder „Neue Kategorie …" direkt beim Zuordnen): eigene anlegen, umbenennen,
  einfärben, löschen. Gelöschte Kategorien geben ihre Umsätze an die automatische Zuordnung zurück.
- **Gruppen** (Mehr → Gruppen): Umsätze eines Vorhabens zusammenfassen und den Saldo sehen.
- Die Umsatzliste öffnet bei der jüngsten echten Buchung; darüber stehen ausgegraut die erwarteten Buchungen der
  Dauerposten für die nächsten 31 Tage. Eine Umbuchung
  steht als eine Zeile („N26 → Tagesgeld", Betrag ohne Vorzeichen); ein Kauf über PayPal als der Kauf selbst mit
  „PayPal über N26". Die jeweils andere Buchung ist im Umsatz unter „Gegenbuchung" bzw. „Bezahlt über" erreichbar.
- **Positionen** (im Umsatz unter „Enthaltene Positionen"): einen Umsatz wie die Kreditkartenabrechnung in Einzelkäufe
  aufteilen, jeder mit eigener Kategorie und Gruppe. Am Kontostand ändert sich nichts; in Analyse, Budgets und Gruppen
  ersetzen die Positionen den Umsatz, der nicht aufgeteilte Rest bleibt bei ihm. In der Umsatzliste stehen sie
  eingerückt unter ihrem Umsatz. Rabatte und eingelöste Prämienpunkte trägt man als **Gutschrift** ein; sie mindern
  die Summe der Käufe. Kategorie und Gruppe lassen sich im Positionsdialog auch neu anlegen. **Wiederholt sich** (etwa ein Amazon-Spar-Abo) legt einen Posten in den Fixkosten an;
  jede weitere Lieferung wird automatisch in die erste Abrechnung am oder nach ihrem Termin eingetragen. Spar-Abos
  zählen wie andere Fixkosten in Monatsübersicht, Tagesbudget und Kontoprognose – erwartet zum Liefertermin auf dem
  Konto der Abrechnung; ist die Abrechnung gebucht, zählt nur noch sie.
- **Analyse:** Kategorien und größte Empfänger lassen sich antippen und zeigen die zugehörigen Umsätze.
- **Fixkosten:** getrennt nach monatlich, vierteljährlich, jährlich usw., jeweils mit Kategorie und Durchschnitt pro
  Monat; Summen pro Monat, Quartal und Jahr; sortierbar nach Fälligkeit, Kosten oder Kategorie. Ein **Anzeigename**
  dient nur der Anzeige und ändert nichts an der Zuordnung der Zahlungen. Ein erkannter Posten gilt als pausiert, wenn
  die erwartete Buchung mehr als sechs Wochen ausbleibt; er wird von selbst wieder aktiv, sobald eine kommt.
  „Fixkosten im <Monat>" zeigt, was im laufenden Monat schon gebucht und noch fällig ist, seltene Zahlungen hervorgehoben.
  Monatszahlungen werden auch erkannt, wenn der Buchungstag schwankt (je Kalendermonat genau eine Buchung).
  Im Umsatz legt **Als Fixkosten anlegen …** einen Posten aus dieser und gleichen Buchungen an.

## Stand der Erprobung

Im Emulator durchgespielt: Konten und Umsätze anlegen, CSV-Import, Analyse, Fixkosten, Budget, Kredite, Budgets je
Kategorie, Sicherung als Datei mit Wiederherstellung, App-Sperre.

Der Bankabruf über Enable Banking läuft auf einem echten Gerät mit N26, Trade Republic und PayPal. Dabei aufgefallen
und berücksichtigt:

- Trade Republic liefert nur Datum und Betrag, keinen Buchungstext. Solche Zahlungen werden an Konto und Betrag
  wiedererkannt, sobald eine davon von Hand eine Kategorie bekommt.
- Käufe über PayPal stehen bei PayPal und noch einmal als Abbuchung auf dem Bankkonto. Die Bankseite zählt als
  Umbuchung, wenn sich der passende PayPal-Umsatz findet.
- Unterkonten (N26 Spaces) kommen als eigene Konten ohne IBAN.
- Die ganze Historie geben Banken nur etwa eine Stunde nach der Freigabe heraus, danach meist 90 Tage. Die App holt
  deshalb direkt nach dem Verbinden alles ohne Datumsgrenze; für ältere Umsätze die Bank erneut verbinden.
- American Express ist bei Enable Banking für Deutschland nicht gelistet (Stand November 2026) und bleibt beim Datei-Import.

Im Testbuild lässt sich ein Abruf per adb anstoßen, ohne die App-Sperre zu öffnen; das Log `BankDiag` nennt dabei je
Konto, welche Felder die Bank liefert (nur Feldnamen und Anzahl, keine Inhalte):

```
adb shell am broadcast -n net.jandev.financialanalyst/.debug.DebugSyncReceiver
adb logcat -d -s BankDiag:D
```

Noch nicht gegen echte Gegenstellen geprüft, weil dafür persönliche Zugänge nötig sind:

- Upload und Download bei Google Drive.
- Das Mitlesen der Benachrichtigungen echter Banking-Apps; deren Wortlaute können vom Parser abweichen.
- Die CSV-Formate sind nach dem bekannten Aufbau der Exporte gebaut und mit erfundenen Dateien getestet, nicht mit echten.

Echte Kontoauszüge, Schlüsseldateien und Exporte gehören nicht ins Repo (`.gitignore` schließt `*.pem` und `private-data/` aus).

## Noch offen

- Digitale Kassenbons (Lidl, REWE, Netto) – bewusst zurückgestellt.
- Automatischer Abruf für Trade Republic, falls dort kein PSD2-Zugang verfügbar ist.
- Datenbank-Migrationen: Das Schema steht noch auf Version 1 und die App ist inzwischen mit echten Daten im Einsatz;
  jede Schemaänderung braucht ab jetzt eine Migration.
