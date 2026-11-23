"""Receives Walky takes, transcribes them and sends the text as a prompt to an OpenCode session.

Two ports: the pair page (behind Coder's owner-only sharing) issues the token and shows it as a
QR for Walky; the audio port (shared publicly) accepts takes that carry that token.
"""

import base64
import hashlib
import hmac
import html
import json
import logging
import os
import secrets
import threading
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import segno

HEADER = "X-Token"
# Whisper-compatible APIs reject uploads above 25 MB; Walky records about 8 kB per second.
MAX_BODY = 25 * 1024 * 1024

log = logging.getLogger("relay")


@dataclass
class Config:
    directory: str
    opencode_url: str
    opencode_password: str
    stt_url: str
    stt_model: str
    stt_key: str
    public_url: str
    token_file: Path
    pair_port: int = 8787
    audio_port: int = 8788
    bind: str = "127.0.0.1"


def config_from_env() -> Config:
    env = os.environ
    audio_port = int(env.get("AUDIO_PORT", "8788"))
    # Coder sets VSCODE_PROXY_URI to this workspace's port URL template.
    public_url = env.get("PUBLIC_URL") or env["VSCODE_PROXY_URI"].replace("{{port}}", str(audio_port))
    return Config(
        directory=env["WALKY_DIRECTORY"],
        opencode_url=env.get("OPENCODE_URL", "http://127.0.0.1:49374"),
        opencode_password=env["OPENCODE_PASSWORD"],
        stt_url=env.get("STT_URL", "https://api.ackstorm.ai/v1/audio/transcriptions"),
        stt_model=env.get("STT_MODEL", "ackstorm.stt"),
        stt_key=env["STT_KEY"],
        public_url=public_url,
        token_file=Path(env.get("TOKEN_FILE", "/data/token.sha256")),
        pair_port=int(env.get("PAIR_PORT", "8787")),
        audio_port=audio_port,
    )


def digest(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


class Relay:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        auth = base64.b64encode(f"opencode:{cfg.opencode_password}".encode()).decode()
        self.opencode_auth = f"Basic {auth}"

    def pair(self) -> str:
        """Issues a new token, which replaces the previous one, and returns the QR payload."""
        token = secrets.token_urlsafe(32)
        self.cfg.token_file.parent.mkdir(parents=True, exist_ok=True)
        self.cfg.token_file.write_text(digest(token))
        query = urllib.parse.urlencode({"url": self.cfg.public_url, "name": HEADER, "value": token})
        return f"walky://pair?{query}"

    def authorized(self, token: str | None) -> bool:
        try:
            expected = self.cfg.token_file.read_text().strip()
        except FileNotFoundError:
            return False
        return token is not None and hmac.compare_digest(digest(token), expected)

    def _opencode(self, method: str, path: str, body: dict | None = None) -> dict:
        req = urllib.request.Request(
            self.cfg.opencode_url + path,
            method=method,
            data=None if body is None else json.dumps(body).encode(),
            headers={"Authorization": self.opencode_auth, "Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=15) as res:
            return json.load(res)

    def latest_session(self) -> str | None:
        query = urllib.parse.urlencode({"directory": self.cfg.directory, "parentID": "null", "limit": "50"})
        sessions = self._opencode("GET", f"/api/session?{query}")["data"]
        if not sessions:
            return None
        return max(sessions, key=lambda s: s["time"]["updated"])["id"]

    def transcribe(self, audio: bytes) -> str:
        boundary = uuid.uuid4().hex
        body = b"".join([
            f'--{boundary}\r\nContent-Disposition: form-data; name="model"\r\n\r\n{self.cfg.stt_model}\r\n'.encode(),
            f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="take.m4a"\r\n'
            "Content-Type: audio/mp4\r\n\r\n".encode(),
            audio,
            f"\r\n--{boundary}--\r\n".encode(),
        ])
        req = urllib.request.Request(
            self.cfg.stt_url,
            data=body,
            headers={
                "Authorization": f"Bearer {self.cfg.stt_key}",
                "Content-Type": f"multipart/form-data; boundary={boundary}",
            },
        )
        with urllib.request.urlopen(req, timeout=120) as res:
            return json.load(res)["text"].strip()

    def deliver(self, session: str, audio: bytes) -> None:
        try:
            text = self.transcribe(audio)
            if not text:
                log.info("empty transcript, nothing sent")
                return
            self._opencode("POST", f"/api/session/{session}/prompt", {"text": text})
            log.info("prompt sent to %s (%d chars)", session, len(text))
        except (urllib.error.URLError, KeyError, ValueError) as e:
            log.error("take lost: %s", e)


PAIR_PAGE = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Pair Walky</title>
<style>body{{font:16px/1.5 system-ui,sans-serif;max-width:28rem;margin:3rem auto;padding:0 1rem;text-align:center}}
button{{font:inherit;padding:.6rem 1.6rem;border-radius:99px;border:0;background:#1F6B5B;color:#fff;cursor:pointer}}
svg{{width:100%;height:auto}}</style></head><body><h1>Pair Walky</h1>{body}</body></html>"""

PAIR_FORM = """<p>Pairing creates a new token. The phone paired before stops working.</p>
<form method="post"><button>Pair</button></form>"""


def pair_handler(relay: Relay):
    class Handler(BaseHTTPRequestHandler):
        def _page(self, body: str) -> None:
            data = PAIR_PAGE.format(body=body).encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            self._page(PAIR_FORM)

        def do_POST(self):
            qr = segno.make(relay.pair(), error="m").svg_inline(scale=6, border=2)
            self._page(f"<p>In Walky, open Settings and tap <b>Scan pairing QR</b>.</p>{qr}"
                       f"<p><small>Sends to {html.escape(relay.cfg.public_url)}</small></p>")

        def log_message(self, *args):
            pass

    return Handler


def audio_handler(relay: Relay):
    class Handler(BaseHTTPRequestHandler):
        def _status(self, code: int) -> None:
            self.send_response(code)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_POST(self):
            if not relay.authorized(self.headers.get(HEADER)):
                return self._status(401)
            size = int(self.headers.get("Content-Length") or 0)
            if size <= 0:
                return self._status(400)
            if size > MAX_BODY:
                return self._status(413)
            audio = self.rfile.read(size)
            try:
                session = relay.latest_session()
            except (urllib.error.URLError, KeyError, ValueError) as e:
                log.error("opencode unreachable: %s", e)
                return self._status(502)
            if session is None:
                log.error("no session in %s", relay.cfg.directory)
                return self._status(409)
            # Walky only needs to know the take arrived; transcription and the agent's turn take longer.
            self._status(202)
            threading.Thread(target=relay.deliver, args=(session, audio), daemon=True).start()

        def log_message(self, fmt, *args):
            log.info("%s %s", self.address_string(), fmt % args)

    return Handler


def servers(relay: Relay) -> tuple[ThreadingHTTPServer, ThreadingHTTPServer]:
    cfg = relay.cfg
    return (
        ThreadingHTTPServer((cfg.bind, cfg.pair_port), pair_handler(relay)),
        ThreadingHTTPServer((cfg.bind, cfg.audio_port), audio_handler(relay)),
    )


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    cfg = config_from_env()
    pair, audio = servers(Relay(cfg))
    threading.Thread(target=pair.serve_forever, daemon=True).start()
    log.info("pair page on %d, audio on %d, prompts to the latest session in %s", cfg.pair_port, cfg.audio_port, cfg.directory)
    audio.serve_forever()


if __name__ == "__main__":
    main()
