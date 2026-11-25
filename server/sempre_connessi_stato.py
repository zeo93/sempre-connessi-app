#!/usr/bin/env python3
"""sempre-connessi-stato: piccolo server HTTP che espone lo stato della linea all'app Android.

E' un servizio SEPARATO dal demone di failover: legge il file di stato che il
demone gia' scrive (/run/sempre-connessi/state.json), misura il ping e tiene lo
storico dei guasti. Se questo servizio si ferma o si rompe, il failover continua
a funzionare come prima.

Indirizzi (porta predefinita 8090):
  GET /stato                       stato completo in JSON
  GET /stato?attendi=N&max=50      come sopra, ma aspetta (fino a "max" secondi)
                                   che il numero di versione sia diverso da N:
                                   l'app resta in ascolto e riceve subito i cambi
  GET /                            pagina semplice da aprire nel browser

Solo libreria standard di Python 3. Richiede i comandi ping e systemctl.
"""

from __future__ import annotations

import argparse
import hmac
import json
import logging
import re
import shlex
import subprocess
import threading
import time
from collections import deque
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

DEFAULT_CONF = "/etc/sempre-connessi/sempre-connessi.conf"
STORICO_FILE = "/var/lib/sempre-connessi-stato/storico.json"
STORICO_MAX = 50

log = logging.getLogger("sempre-connessi-stato")


# ---------------------------------------------------------------------------
# Configurazione: stesso file del demone, si leggono solo le chiavi che servono
# ---------------------------------------------------------------------------
class Config:
    def __init__(self) -> None:
        self.ftth_if = "eth0"
        self.probe_targets = ["1.1.1.1", "8.8.8.8", "9.9.9.9"]
        self.probe_mark = 100
        self.state_file = "/run/sempre-connessi/state.json"
        self.stato_porta = 8090
        self.stato_indirizzo = "0.0.0.0"
        self.stato_token = ""          # vuoto = nessuna password
        self.stato_ping_ogni = 10.0     # secondi tra una misura di ping e l'altra

    @classmethod
    def load(cls, path: str) -> "Config":
        cfg = cls()
        p = Path(path)
        if not p.exists():
            log.warning("%s non trovato, uso i valori predefiniti", path)
            return cfg
        for raw in p.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            attr = key.strip().lower()
            if not hasattr(cfg, attr):
                continue  # chiavi del demone che qui non servono
            parts = shlex.split(value, comments=True)
            value = parts[0] if parts else ""
            current = getattr(cfg, attr)
            try:
                if isinstance(current, list):
                    setattr(cfg, attr, value.split())
                elif isinstance(current, int):
                    setattr(cfg, attr, int(value))
                elif isinstance(current, float):
                    setattr(cfg, attr, float(value))
                else:
                    setattr(cfg, attr, value)
            except ValueError:
                log.warning("valore non valido per %s: %r", key.strip(), value)
        if cfg.state_file.endswith("/state"):
            cfg.state_file += ".json"
        return cfg


# ---------------------------------------------------------------------------
# Misure
# ---------------------------------------------------------------------------
_RTT = re.compile(r"=\s*[\d.]+/([\d.]+)/")


def ping_ms(target: str, *extra: str) -> float | None:
    """Tempo medio di risposta in ms, None se non risponde."""
    try:
        res = subprocess.run(["ping", "-n", "-q", "-c", "1", "-W", "2", *extra, target],
                             capture_output=True, text=True, timeout=5)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if res.returncode != 0:
        return None
    m = _RTT.search(res.stdout)
    return round(float(m.group(1)), 1) if m else None


def byte_interfaccia(ifc: str) -> int | None:
    base = Path(f"/sys/class/net/{ifc}/statistics")
    try:
        return int((base / "rx_bytes").read_text()) + int((base / "tx_bytes").read_text())
    except (OSError, ValueError):
        return None


def demone_attivo() -> bool:
    try:
        res = subprocess.run(["systemctl", "is-active", "--quiet", "sempre-connessi"], timeout=5)
        return res.returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def uptime_s() -> int | None:
    try:
        return int(float(Path("/proc/uptime").read_text().split()[0]))
    except (OSError, ValueError, IndexError):
        return None


def iso(ts: float | None) -> str | None:
    return datetime.fromtimestamp(ts).astimezone().isoformat(timespec="seconds") if ts else None


# ---------------------------------------------------------------------------
# Stato condiviso tra il controllore e le richieste HTTP
# ---------------------------------------------------------------------------
class Stato:
    def __init__(self, cfg: Config, storico_file: str) -> None:
        self.cfg = cfg
        self.storico_file = Path(storico_file)
        self.cond = threading.Condition()
        self.versione = 1
        self.stato = "SCONOSCIUTO"
        self.dal: float | None = None
        self.telefono_if: str | None = None
        self.telefono_gw: str | None = None
        self.demone = False
        self.ping_fibra: float | None = None
        self.ping_internet: float | None = None
        self.campioni_fibra: deque[bool] = deque(maxlen=60)  # ultimi 10 minuti
        # guasto in corso
        self.guasto_inizio: float | None = None
        self.guasto_backup = False
        self.byte_inizio: int | None = None
        self.byte_backup = 0
        self.storico: list[dict] = self._carica_storico()

    # -- storico ------------------------------------------------------------
    def _carica_storico(self) -> list[dict]:
        try:
            dati = json.loads(self.storico_file.read_text(encoding="utf-8"))
            return dati if isinstance(dati, list) else []
        except (OSError, ValueError):
            return []

    def _salva_storico(self) -> None:
        try:
            self.storico_file.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.storico_file.with_suffix(".tmp")
            tmp.write_text(json.dumps(self.storico, indent=1), encoding="utf-8")
            tmp.replace(self.storico_file)
        except OSError as exc:
            log.warning("impossibile salvare lo storico: %s", exc)

    # -- aggiornamento --------------------------------------------------------
    def _leggi_file_stato(self) -> dict:
        try:
            return json.loads(Path(self.cfg.state_file).read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}

    def aggiorna(self, now: float) -> None:
        demone = demone_attivo()
        dati = self._leggi_file_stato() if demone else {}
        nuovo = dati.get("state", "SCONOSCIUTO") if demone else "FERMO"
        try:
            dal = datetime.fromisoformat(dati["since"]).timestamp() if dati.get("since") else None
        except ValueError:
            dal = None

        with self.cond:
            cambiato = (nuovo != self.stato or dal != self.dal or demone != self.demone)
            vecchio = self.stato
            self.stato, self.dal, self.demone = nuovo, dal, demone
            self.telefono_if = dati.get("phone_if")
            self.telefono_gw = dati.get("phone_gw")
            self._traccia_guasto(vecchio, nuovo, now)
            if cambiato:
                self.versione += 1
                log.info("stato %s -> %s (versione %d)", vecchio, nuovo, self.versione)
                self.cond.notify_all()

    def misura_ping(self) -> None:
        mark = str(self.cfg.probe_mark)
        fibra = None
        for t in self.cfg.probe_targets:  # passando SEMPRE dalla fibra (come il demone)
            fibra = ping_ms(t, "-m", mark)
            if fibra is not None:
                break
        internet = fibra if self.stato == "NORMAL" else None
        if internet is None:  # dalla rotta attuale (fibra o telefono)
            for t in self.cfg.probe_targets:
                internet = ping_ms(t)
                if internet is not None:
                    break
        with self.cond:
            self.ping_fibra, self.ping_internet = fibra, internet
            self.campioni_fibra.append(fibra is not None)

    def _traccia_guasto(self, vecchio: str, nuovo: str, now: float) -> None:
        """Da chiamare con il lock preso."""
        in_guasto = nuovo in ("DOWN", "BACKUP")
        if in_guasto and self.guasto_inizio is None:
            self.guasto_inizio = self.dal or now
            self.guasto_backup = False
            self.byte_backup = 0
            self.byte_inizio = None
        if nuovo == "BACKUP" and self.telefono_if:
            self.guasto_backup = True
            b = byte_interfaccia(self.telefono_if)
            if b is not None:
                if self.byte_inizio is None or b < self.byte_inizio:
                    self.byte_inizio = b  # inizio backup (o contatore azzerato)
                self.byte_backup = max(self.byte_backup, b - self.byte_inizio)
        elif self.byte_inizio is not None:
            self.byte_inizio = None  # telefono scollegato: i byte gia' contati restano
        if not in_guasto and self.guasto_inizio is not None and vecchio in ("DOWN", "BACKUP"):
            fine = self.dal or now
            self.storico.insert(0, {
                "inizio": iso(self.guasto_inizio),
                "fine": iso(fine),
                "durata_s": int(fine - self.guasto_inizio),
                "backup": self.guasto_backup,
                "mb_backup": round(self.byte_backup / 1e6, 1),
            })
            del self.storico[STORICO_MAX:]
            self._salva_storico()
            self.guasto_inizio = None
        elif not in_guasto:
            self.guasto_inizio = None

    # -- lettura ----------------------------------------------------------------
    def attendi(self, versione: int, max_s: float) -> None:
        with self.cond:
            self.cond.wait_for(lambda: self.versione != versione, timeout=max_s)

    def json(self) -> dict:
        now = time.time()
        with self.cond:
            mese = datetime.now().strftime("%Y-%m")
            mb_mese = sum(g.get("mb_backup", 0) for g in self.storico
                          if str(g.get("inizio", "")).startswith(mese))
            if self.guasto_inizio and iso(self.guasto_inizio).startswith(mese):
                mb_mese += self.byte_backup / 1e6
            campioni = list(self.campioni_fibra)
            return {
                "app": "sempre-connessi",
                "formato": 1,
                "versione": self.versione,
                "ora": iso(now),
                "stato": self.stato,
                "dal": iso(self.dal),
                "da_secondi": int(now - self.dal) if self.dal else None,
                "demone_attivo": self.demone,
                "telefono": ({"interfaccia": self.telefono_if, "gateway": self.telefono_gw}
                             if self.telefono_if else None),
                "ping_fibra_ms": self.ping_fibra,
                "ping_internet_ms": self.ping_internet,
                "fibra_perdita_pct": (round(100 * campioni.count(False) / len(campioni))
                                      if campioni else None),
                "guasto": ({
                    "inizio": iso(self.guasto_inizio),
                    "da_secondi": int(now - self.guasto_inizio),
                    "backup": self.guasto_backup,
                    "mb_backup": round(self.byte_backup / 1e6, 1),
                } if self.guasto_inizio else None),
                "mb_backup_mese": round(mb_mese, 1),
                "storico": self.storico[:20],
                "server_uptime_s": uptime_s(),
            }


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------
PAGINA = """<!doctype html><html lang="it"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sempre connessi</title>
<style>body{font-family:sans-serif;margin:16px;max-width:40em}pre{white-space:pre-wrap}</style>
<h1>Sempre connessi</h1><pre id="s">caricamento...</pre>
<script>
async function f(){try{const r=await fetch('stato'+location.search);
document.getElementById('s').textContent=JSON.stringify(await r.json(),null,2)}catch(e){}}
f();setInterval(f,5000);
</script></html>
"""


def crea_handler(stato: Stato, token: str):
    class Handler(BaseHTTPRequestHandler):
        server_version = "sempre-connessi-stato/1"

        def log_message(self, fmt, *args):  # niente log per ogni richiesta
            pass

        def _invia(self, codice: int, corpo: bytes, tipo: str) -> None:
            self.send_response(codice)
            self.send_header("Content-Type", tipo)
            self.send_header("Content-Length", str(len(corpo)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(corpo)

        def do_GET(self):
            url = urlparse(self.path)
            q = parse_qs(url.query)
            if token:
                dato = self.headers.get("X-Token") or (q.get("token") or [""])[0]
                if not hmac.compare_digest(dato.encode(), token.encode()):
                    self._invia(401, b'{"errore":"token mancante o sbagliato"}', "application/json")
                    return
            if url.path in ("/", "/index.html"):
                self._invia(200, PAGINA.encode(), "text/html; charset=utf-8")
            elif url.path == "/stato":
                try:
                    attendi = int(q["attendi"][0]) if "attendi" in q else None
                    max_s = min(max(float(q.get("max", ["50"])[0]), 1.0), 120.0)
                except ValueError:
                    self._invia(400, b'{"errore":"parametri non validi"}', "application/json")
                    return
                if attendi is not None:
                    stato.attendi(attendi, max_s)
                corpo = json.dumps(stato.json(), ensure_ascii=False).encode()
                self._invia(200, corpo, "application/json; charset=utf-8")
            else:
                self._invia(404, b'{"errore":"non trovato"}', "application/json")

    return Handler


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="sempre-connessi-stato", description=__doc__.splitlines()[0])
    ap.add_argument("-c", "--config", default=DEFAULT_CONF)
    ap.add_argument("--storico", default=STORICO_FILE)
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    cfg = Config.load(args.config)
    stato = Stato(cfg, args.storico)

    def controllore():
        while True:
            try:
                stato.aggiorna(time.time())
            except Exception:
                log.exception("errore nell'aggiornamento dello stato")
            time.sleep(2)

    def misuratore():  # thread a parte: un ping lento non ritarda gli avvisi
        while True:
            try:
                stato.misura_ping()
            except Exception:
                log.exception("errore nella misura del ping")
            time.sleep(cfg.stato_ping_ogni)

    threading.Thread(target=controllore, daemon=True).start()
    threading.Thread(target=misuratore, daemon=True).start()
    ThreadingHTTPServer.daemon_threads = True
    srv = ThreadingHTTPServer((cfg.stato_indirizzo, cfg.stato_porta), crea_handler(stato, cfg.stato_token))
    log.info("in ascolto su %s:%d%s", cfg.stato_indirizzo, cfg.stato_porta,
             " (con token)" if cfg.stato_token else "")
    srv.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
