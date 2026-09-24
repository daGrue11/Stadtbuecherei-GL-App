# Technische Details

Hintergrund zur [Stadtbücherei-GL-App](../README.md): wie man sie selbst baut und wie
das Auslesen der Bibliotheksseite funktioniert.

---

## Selbst bauen

Es wird kein Android Studio gebraucht — GitHub baut den APK:

```bash
git clone https://github.com/daGrue11/Stadtbuecherei-GL-App.git
cd Stadtbuecherei-GL-App
```

Im eigenen Fork unter **Actions** den Workflow *Android APK bauen* starten und
den APK anschließend unter *Artifacts* herunterladen. Ohne hinterlegten
Signaturschlüssel wird mit einem Debug-Schlüssel signiert — der APK läuft, kann
aber keine offizielle Version aus den Releases überschreiben.

Mit Android Studio: Projektordner öffnen, Gradle synchronisieren, `Run`.

## Wie es funktioniert

Die Stadtbücherei nutzt **OCLC OPEN** auf DotNetNuke (ASP.NET WebForms). Eine
offizielle Schnittstelle gibt es nicht, die App liest die Website aus. Das
Wesentliche steckt in [`LibraryClient.kt`](https://github.com/daGrue11/Stadtbuecherei-GL-App/blob/main/app/src/main/java/de/bibgl/konto/data/LibraryClient.kt):

- **Login** ist ein WebForms-Postback auf `/Login` mit `__VIEWSTATE` und
  `__EVENTVALIDATION` aus dem Formular.
- **Das ganze Konto** steht danach in einer einzigen Seite `/Mein-Konto` — alle
  Reiter sind serverseitig gerendert, es braucht keinen Klick pro Reiter.
- **Verlängerbarkeit** steht *nicht* im HTML, sondern wird per AJAX von
  `PatronAccountService.asmx/IsCatalogueCopyExtendable` nachgeladen. Die App ruft
  denselben JSON-Endpunkt auf und bekommt pro Medium den Status, eine Begründung
  und das neue Fristdatum.
- **Verlängern ist zweistufig.** Der Klick auf „Verlängern" verlängert noch
  nichts, sondern öffnet den Dialog „Verlängerung bestätigen", der anfallende
  Verlängerungs- und Säumnisgebühren nennt. Erst dessen Button führt sie aus.
  Fallen Gebühren an, fragt die App vorher nach, statt sie stillschweigend zu
  akzeptieren.
- **Element-IDs** werden über ihre Endung angesprochen (`[id$=lblFeeTotalData]`),
  weil die Präfixe eine wechselnde Modulnummer enthalten — und immer auf das
  jeweilige Panel eingegrenzt, weil die Seite ausgeblendete Dialoge mit
  denselben ID-Endungen enthält.
- **Erfolg einer Verlängerung** wird daran gemessen, ob sich die Frist
  tatsächlich verschoben hat, nicht an den Meldungstexten der Seite.
- **Mehrere Konten** bekommen je einen eigenen HTTP-Client mit eigenem
  Cookie-Jar; die Seite kennt nur eine Anmeldung pro Sitzung.

Weil die App eine Website ausliest, kann ein Umbau durch die Bibliothek die
Anzeige stören. Die Selektoren sind bewusst so gewählt, dass sie Änderungen an
Layout und Modulnummern überstehen — aber eine Garantie ist das nicht.

## Technik

Kotlin · Jetpack Compose (Material 3) · OkHttp · Jsoup · WorkManager ·
EncryptedSharedPreferences · minSdk 26 · Gebaut mit GitHub Actions

## Signierung und Releases

Android installiert eine neue Version nur über eine vorhandene, wenn beide mit
demselben Schlüssel signiert sind. Ohne feste Konfiguration erzeugt Gradle bei
jedem CI-Lauf einen neuen Zufallsschlüssel — die GitHub-Runner werden jedes Mal
frisch aufgesetzt. Updates schlagen dann mit „App nicht installiert" fehl.

Deshalb liegt ein fester Schlüssel in den Repository-Secrets:

| Secret | Inhalt |
|---|---|
| `KEYSTORE_BASE64` | der PKCS12-Keystore, base64-kodiert |
| `KEYSTORE_PASSWORD` | Passwort für Store und Schlüssel (Alias `bibgl`) |

Der Workflow schreibt daraus `app/keystore.jks`, bevor Gradle läuft. Die Datei
ist in `.gitignore` ausgeschlossen und darf nicht ins Repository.

Fehlt das Secret — etwa in einem Fork — wird mit dem Debug-Schlüssel signiert.
Der APK läuft, kann aber keine offizielle Version aus den Releases überschreiben.

### Eine Version veröffentlichen

1. `versionCode` und `versionName` in [`app/build.gradle.kts`](../app/build.gradle.kts)
   erhöhen. `versionCode` muss streng steigen, sonst verweigert Android das Update.
2. Änderungen committen und pushen.
3. Passenden Tag setzen:

```bash
git tag v1.6
git push origin v1.6
```

Der Tag-Push baut den APK und legt den GitHub-Release samt Datei an. Ein
Vorab-Check bricht ab, wenn der Tag nicht zum `versionName` im Build passt —
sonst driften Dateiname und tatsächliche App-Version auseinander.

> Nur Release-Dateien sind ohne GitHub-Login herunterladbar. Die Artefakte eines
> normalen Builds sind es nicht — deshalb der Umweg über Tags.
