# Sempre connessi: app Android

App Android per il sistema "sempre connessi" (failover fibra → hotspot del telefono).
Mostra lo stato della linea di casa e manda notifiche proprie, ad alta priorità, quando
la fibra cade, quando la casa passa sul telefono e quando la fibra torna.

```
 server di casa (192.168.1.2)                      telefono
 ┌──────────────────────────────┐   Wi-Fi di casa  ┌──────────────────────┐
 │ sempre-connessi  (failover)  │ ───────────────▶ │ app "Sempre connessi" │
 │   └ /run/.../state.json      │   o hotspot      │  notifiche + stato    │
 │ sempre-connessi-stato :8090  │ ◀─────────────── │  (richiesta lunga)    │
 └──────────────────────────────┘                  └──────────────────────┘
```

## Cosa fa l'app

- **Stato in tempo reale**: Fibra OK / Fibra giù / Backup attivo / Servizio fermo, da quanto tempo.
- **Linea**: ping sulla fibra, ping attuale, percentuale di ping persi negli ultimi 10 minuti,
  da quanto è acceso il server.
- **Giga**: consumati dal telefono nel guasto in corso e nel mese.
- **Storico** degli ultimi guasti (inizio, durata, se c'è stato backup e quanti MB).
- **Notifiche**:
  - *Fibra giù* (priorità alta, con pulsante "Attiva hotspot");
  - *Backup attivo*;
  - *Fibra tornata* (durata del guasto, giga usati, "puoi spegnere l'hotspot");
  - *Servizio fermo* se il demone sul server non gira;
  - *Server non raggiungibile* (solo se il telefono è su un Wi-Fi da più di 3 minuti).
- **MacroDroid**: a ogni cambio di stato l'app manda un intent
  `it.sempreconnessi.STATO` con l'extra `stato` (`NORMAL`, `DOWN`, `BACKUP`, `FERMO`).
  In MacroDroid: trigger *Intent ricevuto* → azione *Hotspot Wi-Fi on/off*.
  Così non serve più ntfy per far partire la macro.

Android non permette a un'app normale di accendere l'hotspot: per quello resta MacroDroid
(o il pulsante nella notifica, che apre direttamente la schermata dell'hotspot).

### Come resta in ascolto

L'app tiene un servizio in primo piano (notifica fissa "Sempre connessi", al minimo) che fa
una richiesta lunga al server: il server la tiene aperta fino a 50 secondi e risponde
**subito** quando lo stato cambia. Consuma pochissima batteria (come ntfy).

- Sul Wi-Fi di casa la richiesta è legata alla rete Wi-Fi, quindi funziona anche quando
  Android segna il Wi-Fi "senza internet" perché la fibra è giù.
- Quando il telefono fa da hotspot, il server è un client dell'hotspot con un indirizzo
  che cambia: l'app lo cerca da sola sulla rete dell'hotspot e se lo ricorda.

## Installare l'APK

1. Ogni modifica su `main` compila l'APK con GitHub Actions e lo pubblica nella pagina
   **Releases** del repository (anche come artifact nella scheda Actions).
2. Dal telefono apri la release, scarica `sempre-connessi-N.apk` e installalo
   (Android chiede di consentire l'installazione da browser/File: "origini sconosciute").
3. Apri l'app, consenti le notifiche, premi **Togli il risparmio batteria per l'app**.
4. Se il server ha un token (vedi sotto), scrivilo in Impostazioni e premi Salva.

Su alcuni telefoni (Xiaomi, Huawei, Samsung) conviene anche permettere l'"avvio automatico"
dell'app e bloccarla tra le app recenti, altrimenti il sistema la chiude.

### Firma degli aggiornamenti

Senza configurazione ogni compilazione usa una chiave di debug diversa, e per installare
una versione nuova bisogna prima disinstallare quella vecchia. Per evitarlo, crea una
chiave una volta sola e mettila nei *secrets* del repository
(Settings → Secrets and variables → Actions):

```bash
keytool -genkeypair -v -keystore firma.jks -alias sempreconnessi -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=Sempre connessi"
base64 -w0 firma.jks   # copia l'output
```

- `FIRMA_KEYSTORE_B64` = output di `base64`
- `FIRMA_PASSWORD` = la password scelta

## Server: servizio di stato

`server/sempre_connessi_stato.py` è un servizio **separato** dal demone di failover: legge
`/run/sempre-connessi/state.json` (già scritto dal demone), misura il ping e tiene lo storico.
Se si ferma, il failover continua a funzionare come prima. Solo libreria standard di Python 3.

Installazione (sul server, da root):

```bash
install -D -m 755 server/sempre_connessi_stato.py /usr/local/lib/sempre-connessi/sempre_connessi_stato.py
install -m 644 server/sempre-connessi-stato.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now sempre-connessi-stato
curl -s http://127.0.0.1:8090/stato | python3 -m json.tool
```

Dal telefono sul Wi-Fi di casa si può aprire anche `http://192.168.1.2:8090/` nel browser.

Impostazioni facoltative, nello stesso file `/etc/sempre-connessi/sempre-connessi.conf`:

```bash
STATO_PORTA=8090
STATO_INDIRIZZO="0.0.0.0"
# password per l'app (consigliata se sul Wi-Fi di casa ci sono ospiti)
STATO_TOKEN="una-parola-lunga"
STATO_PING_OGNI=10
```

Se sul server c'è un firewall (ufw), aprire la porta: `ufw allow 8090/tcp`.

### Risposta di `GET /stato`

```json
{
  "app": "sempre-connessi", "formato": 1, "versione": 7,
  "stato": "BACKUP", "dal": "2026-11-25T14:02:10+01:00", "da_secondi": 312,
  "demone_attivo": true,
  "telefono": {"interfaccia": "wlan0", "gateway": "10.12.34.1"},
  "ping_fibra_ms": null, "ping_internet_ms": 48.3, "fibra_perdita_pct": 40,
  "guasto": {"inizio": "2026-11-25T14:01:40+01:00", "da_secondi": 342, "backup": true, "mb_backup": 85.2},
  "mb_backup_mese": 1250.4,
  "storico": [{"inizio": "...", "fine": "...", "durata_s": 600, "backup": true, "mb_backup": 310.0}],
  "server_uptime_s": 86400
}
```

`GET /stato?attendi=7&max=50` aspetta finché la versione è diversa da 7 (al massimo 50 s).

## Struttura

```
app/                      app Android (Kotlin, nessuna libreria esterna, Android 8+)
server/                   servizio di stato per il server + unità systemd
.github/workflows/apk.yml compilazione APK e release
```
