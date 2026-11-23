# Walky relay

Receives Walky takes, transcribes them with an OpenAI-compatible speech-to-text API and sends
the text as a prompt to the most recently updated OpenCode session of one project. Anything
that follows that session, such as a Telegram bot, shows the prompt and the agent's reply.
The relay answers Walky with 202 before transcribing; the reply never comes back through Walky.

Two ports, both on 127.0.0.1:

| Port | Serves | Coder sharing |
|------|--------|---------------|
| 8787 | Pair page: each **Pair** issues a new token, revokes the previous one and shows it as a QR | Owner only (default) |
| 8788 | Audio: `POST` with header `X-Token`, at most 25 MB | **Public**, HTTPS |

Answers on 8788: 202 accepted, 401 bad token, 400 empty body, 413 too large, 409 no session
in the project, 502 OpenCode unreachable. A take lost after the 202 is only logged.

## Run

It must run on the machine where OpenCode listens (`opencode serve --service`, port 49374).

1. Create `server/.env` (gitignored):
   ```sh
   WALKY_DIRECTORY=/absolute/path/of/the/project
   STT_KEY=<speech-to-text API key>
   ```
2. Start it; the OpenCode password is read at start, so run this again if it changes:
   ```sh
   cd server
   OPENCODE_PASSWORD="$(opencode service get password)" docker compose up -d --build
   ```
3. In Coder, share port 8788 as **Public** (Ports panel, or `PATCH /api/v2/workspaces/<id>/port-share`).
   The workspace template must allow public sharing.
4. Open port 8787 through Coder, press **Pair**, and in Walky tap **Settings → Scan pairing QR**.

The public URL comes from Coder's `VSCODE_PROXY_URI`; set `PUBLIC_URL` to override it. Other
settings: `OPENCODE_URL`, `STT_URL`, `STT_MODEL` (default `ackstorm.stt`), `PAIR_PORT`,
`AUDIO_PORT`. The token is stored only as a SHA-256 hash, in the `relay-data` volume.

## Test

```sh
docker build --target test server
```
