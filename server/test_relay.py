import http.client
import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import relay


class Fake:
    """An HTTP server that records requests and answers with canned JSON."""

    def __init__(self, routes):
        self.routes = routes
        self.requests = []
        self.got = threading.Event()
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def _answer(self):
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                fake.requests.append((self.command, self.path, dict(self.headers), body))
                code, payload = fake.routes(self.command, urllib.parse.urlparse(self.path).path)
                data = json.dumps(payload).encode()
                self.send_response(code)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                fake.got.set()

            do_GET = do_POST = _answer

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


SESSIONS = [
    {"id": "ses_old", "time": {"created": 1, "updated": 10}},
    {"id": "ses_new", "time": {"created": 2, "updated": 20}},
]


class RelayTest(unittest.TestCase):
    def setUp(self):
        self.sessions = SESSIONS
        self.opencode = Fake(self.opencode_routes)
        self.stt = Fake(lambda method, path: (200, {"text": " hello agent "}))
        self.tmp = tempfile.TemporaryDirectory()
        self.relay = relay.Relay(relay.Config(
            directory="/work/project",
            opencode_url=self.opencode.url,
            opencode_password="oc-pass",
            stt_url=self.stt.url + "/v1/audio/transcriptions",
            stt_model="test.stt",
            stt_key="stt-key",
            public_url="https://8788--main--ws--me.coder.example",
            token_file=Path(self.tmp.name) / "token.sha256",
            pair_port=0,
            audio_port=0,
        ))
        self.pair_server, self.audio_server = relay.servers(self.relay)
        for s in (self.pair_server, self.audio_server):
            threading.Thread(target=s.serve_forever, daemon=True).start()

    def tearDown(self):
        for s in (self.pair_server, self.audio_server):
            s.shutdown()
            s.server_close()
        self.opencode.close()
        self.stt.close()
        self.tmp.cleanup()

    def opencode_routes(self, method, path):
        if method == "GET" and path == "/api/session":
            return 200, {"data": self.sessions, "cursor": None}
        if method == "POST" and path.endswith("/prompt"):
            return 200, {"data": {}}
        return 404, {}

    def url(self, server):
        return f"http://127.0.0.1:{server.server_address[1]}/"

    def post_take(self, token, body=b"fake-aac"):
        headers = {"Content-Type": "audio/mp4"}
        if token is not None:
            headers[relay.HEADER] = token
        req = urllib.request.Request(self.url(self.audio_server), data=body, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=5) as res:
                return res.status
        except urllib.error.HTTPError as e:
            return e.code

    def token(self):
        payload = urllib.parse.urlparse(self.relay.pair())
        return urllib.parse.parse_qs(payload.query)["value"][0]

    def test_pair_payload_carries_url_header_and_token(self):
        payload = urllib.parse.urlparse(self.relay.pair())
        self.assertEqual(("walky", "pair"), (payload.scheme, payload.netloc))
        query = urllib.parse.parse_qs(payload.query)
        self.assertEqual(["https://8788--main--ws--me.coder.example"], query["url"])
        self.assertEqual([relay.HEADER], query["name"])
        self.assertGreaterEqual(len(query["value"][0]), 40)

    def test_token_is_stored_as_a_hash(self):
        token = self.token()
        self.assertNotIn(token, self.relay.cfg.token_file.read_text())

    def test_unpaired_relay_rejects_everything(self):
        self.assertEqual(401, self.post_take("anything"))

    def test_missing_or_wrong_token_is_401_and_nothing_is_called(self):
        self.token()
        self.assertEqual(401, self.post_take(None))
        self.assertEqual(401, self.post_take("wrong"))
        self.assertEqual([], self.opencode.requests)
        self.assertEqual([], self.stt.requests)

    def test_new_pairing_revokes_the_previous_token(self):
        old = self.token()
        self.token()
        self.assertEqual(401, self.post_take(old))

    def test_pair_page_shows_a_qr(self):
        with urllib.request.urlopen(self.url(self.pair_server), timeout=5) as res:
            self.assertIn(b"<form", res.read())
        req = urllib.request.Request(self.url(self.pair_server), data=b"", method="POST")
        with urllib.request.urlopen(req, timeout=5) as res:
            self.assertIn(b"<svg", res.read())
        self.assertTrue(self.relay.cfg.token_file.exists())

    def test_valid_take_is_transcribed_and_prompted_to_the_latest_session(self):
        self.assertEqual(202, self.post_take(self.token()))
        prompt = self.wait_for_prompt()

        _, path, headers, body = prompt
        self.assertEqual("/api/session/ses_new/prompt", path)
        self.assertEqual({"text": "hello agent"}, json.loads(body))
        self.assertTrue(headers["Authorization"].startswith("Basic "))

        _, list_path, _, _ = self.opencode.requests[0]
        query = urllib.parse.parse_qs(urllib.parse.urlparse(list_path).query)
        self.assertEqual(["/work/project"], query["directory"])

        _, stt_path, stt_headers, stt_body = self.stt.requests[0]
        self.assertEqual("/v1/audio/transcriptions", stt_path)
        self.assertEqual("Bearer stt-key", stt_headers["Authorization"])
        self.assertIn(b'name="model"\r\n\r\ntest.stt', stt_body)
        self.assertIn(b'filename="take.m4a"', stt_body)
        self.assertIn(b"fake-aac", stt_body)

    def test_no_session_is_409_and_nothing_is_transcribed(self):
        self.sessions = []
        self.assertEqual(409, self.post_take(self.token()))
        self.assertEqual([], self.stt.requests)

    def test_empty_body_is_400(self):
        self.assertEqual(400, self.post_take(self.token(), body=b""))

    def test_oversized_take_is_413(self):
        # The declared length alone is refused, before any body is read.
        c = http.client.HTTPConnection("127.0.0.1", self.audio_server.server_address[1], timeout=5)
        c.putrequest("POST", "/")
        c.putheader(relay.HEADER, self.token())
        c.putheader("Content-Length", str(relay.MAX_BODY + 1))
        c.endheaders()
        self.assertEqual(413, c.getresponse().status)
        c.close()

    def wait_for_prompt(self):
        for _ in range(50):
            prompts = [r for r in self.opencode.requests if r[0] == "POST"]
            if prompts:
                return prompts[0]
            self.opencode.got.wait(0.1)
            self.opencode.got.clear()
        self.fail("no prompt reached OpenCode within 5 s")


if __name__ == "__main__":
    unittest.main()
