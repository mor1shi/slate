# Slate

An open-source whiteboard for drawing, notes and images: a JavaFX client backed
by local SQLite, with an optional WebSocket server and MongoDB adapter. It works
offline on a single machine, on a local network, or as a self-hosted service on
the internet.

The logo lives in `branding/slate.svg` (a chalk stroke on a graphite background);
alternative variants are in `branding/`.

## Features

- Boards up to 4000×4000: freehand drawing, text, shapes, images, stickers, zoom
  and panning.
- Selection tool: marquee selection (fully enclosed or touching), Shift/Ctrl
  modifiers, group move, resize, styles, clipboard, and a single Undo entry per
  group action.
- Real-time collaboration with participant cursors in their personal colours.
- Profiles: name, avatar and personal colour, shown to others on the board.
- Board list with search, rename and delete.
- One-time invite codes with the **editor** or **view-only** role.
- Passwordless sign-in: a device key identifies you; the server stores only its
  SHA-256 hash.
- Dark theme and a floating tool dock.
- No cloud, no telemetry: your data stays on your machine or server.

## Three ways to run it

| | Who uses it | Server | Client address | Protection |
|---|---|---|---|---|
| **1. One computer** | just you | none — local SQLite | — | nothing leaves the machine |
| **2. Local network** | several people on one network | `run-server --lan` on `0.0.0.0` | `ws://192.168.x.x:8765` | trusted network + one-time codes |
| **3. Internet** | people on different networks | server + domain + TLS | `wss://canvas.example.com` | mandatory TLS, rate limits, closed registration |

In every mode, access to a specific board is granted only by invitation. The
device key is effectively your account — never publish it.

## Security: how sign-in works

On first launch the client generates a random key (32 bytes, Base64URL) in
`~/.config/canvasproject/device.json` (`chmod 600` on Linux/macOS). The server
receives the key, computes its SHA-256 and finds or creates a profile. There are
no passwords and no email addresses.

**How safe this is per scenario:**

- **One computer** — the best option: the key never leaves the machine and no
  network port is opened at all.
- **Local network** — acceptable on a trusted network (home/office). Remember:
  without TLS the key and board contents travel in the clear. On guest Wi-Fi use
  TLS or keep the server off the LAN.
- **Internet** — the key is effectively a password. **Never expose the server
  without `wss://`**: anyone who intercepts the traffic gets full access to your
  boards. With TLS (Caddy/nginx or the built-in keystore) the model is safe for
  small teams.

Limitations worth knowing:

- losing the key means losing the boards (there is no recovery, as with an SSH
  key);
- keys cannot be revoked individually: a new key means a new account (old boards
  stay with the old one);
- there is no 2FA and no sign-in audit log.

That is not enough for a public multi-tenant service — it would need recoverable
accounts and moderation. For personal use and small teams, "key + invitations"
is a deliberate trade-off in favour of simplicity.

## Installation

The **client** needs **JDK 17+**. The optional **server** needs **JDK 21+** and
**MongoDB 6+**; solo mode does not use either.

### Linux

```bash
# Debian/Ubuntu — example, package names vary by distribution
sudo apt install openjdk-21-jdk mongodb
git clone https://github.com/mor1shi/canvasProject.git slate && cd slate
./run-client.sh        # client only, no server required
```

To use the network server in another terminal:

```bash
./run-server.sh        # starts mongod too if it is installed
```

Without a local MongoDB (Docker):

```bash
docker run -d --name canvas-mongo -p 127.0.0.1:27017:27017 \
  -v canvas-mongo:/data/db mongo:7
```

### Windows

1. JDK 21: `winget install EclipseAdoptium.Temurin.21.JDK`
2. MongoDB Community Server — install it as a Windows service (tick
   "Install as a Service") if you need the server.
3. Download the repository (Git or ZIP) and open PowerShell in the project
   folder:

```powershell
.\run-client.ps1          # or .\run-client.bat
.\run-server.ps1          # only for network mode, in a second window
```

The scripts locate JDK 17/21 automatically. If Java is not found, set
`JAVA_HOME`.

## Scenario 1. One computer

```bash
./run-client.sh          # Linux/macOS
.\run-client.ps1         # Windows
```

No server is started. On the first launch the client creates an "Untitled"
board, and every change is saved to the local SQLite database. The device key
persists between runs.

## Scenario 2. Local network

On the server machine:

```bash
./run-server.sh --lan    # Linux/macOS, listens on 0.0.0.0
.\run-server.ps1 -Lan    # Windows
```

Find the server IP (`ip a` / `ipconfig`) and open port `8765` in the firewall:

```bash
sudo ufw allow 8765/tcp                 # Ubuntu
sudo firewall-cmd --add-port=8765/tcp   # Fedora
```

On each client: **"Server…"** → `ws://192.168.1.10:8765` (your server address) →
reconnect. Then the owner opens a board → menu → "Invite code" → role → shares
the 8-character code. The guest uses **"Open by code"**.

Boards are visible only to members. Codes are single-use and valid for 24 hours.

## Scenario 3. Internet

Recommended setup: the server on `127.0.0.1:8765`, a reverse proxy with automatic
HTTPS (Caddy) in front, and clients connecting over `wss://`.

`Caddyfile`:

```
canvas.example.com {
    reverse_proxy 127.0.0.1:8765
}
```

```bash
caddy run        # or systemctl enable --now caddy
```

Client: **"Server…"** → `wss://canvas.example.com`.

Do not expose port 8765 itself and do not use `ws://` over the internet.
Additionally:

- disable registration of new devices: `ALLOW_NEW_DEVICES=0` in `.env` (new
  people can then join only with a copied key);
- enable a firewall: only 80/443 should be reachable from outside;
- keep backups (see below).

### Built-in TLS (no proxy)

If you do not need a proxy, the server can terminate `wss` itself:

```bash
keytool -genkeypair -alias canvas -keyalg RSA -keysize 2048 -storetype PKCS12 \
  -keystore canvas.p12 -validity 3650 -dname "CN=canvas.example.com"

TLS_KEYSTORE=/path/canvas.p12 TLS_KEYSTORE_PASSWORD=secret ./run-server.sh
```

Clients do not trust a self-signed certificate by default. For testing:
`JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/path/truststore.p12 -Djavax.net.ssl.trustStorePassword=secret" ./run-client.sh`.
For long-term use, Caddy/Let's Encrypt is simpler.

## Server settings (`.env`)

| Variable | Default | Meaning |
|---|---|---|
| `WS_HOST` | `127.0.0.1` | listen address (`0.0.0.0` — LAN) |
| `WS_PORT` | `8765` | WebSocket port |
| `MONGO_URI` | `mongodb://localhost:27017` | MongoDB |
| `MONGO_DB` | `canvas_collab` | database |
| `ALLOW_NEW_DEVICES` | `1` | `0` — known keys only |
| `ALLOW_PASSWORD_AUTH` | `0` | legacy password sign-in |
| `MAX_CONNECTIONS_PER_IP` | `10` | connection limit |
| `MAX_MESSAGES_PER_10S` | `600` | anti-flood limit |
| `AUTH_TIMEOUT_SECONDS` | `20` | time allowed for authentication |
| `SLATE_ASSETS_DIR` | `<data dir>/assets` | image files served by the server |
| `TLS_KEYSTORE`, `TLS_KEYSTORE_PASSWORD` | — | enable `wss` |

The client stores the server address in `~/.config/canvasproject/settings.json`
and the device key in `device.json`.

## Roles and invitations

- **Owner** — rename, delete, invite editors and viewers.
- **Editor** — draws and changes content, invites viewers only.
- **Viewer** — sees the board in real time; tools, moving or changing elements,
  background and size are locked (both in the client and on the server).

An invite code has 8 characters, is single-use and valid for 24 hours. It can be
revoked by generating a new one or deleting it from the database; a revoke
button in the UI is planned.

## Boards, `.slate` files and backups

Local boards live in SQLite. The exchange and backup format is `.slate` — a zip
archive with `board.json` and the assets the board uses:

- **Export…** and **Import…** in the board list; importing always creates a new
  board and never overwrites an existing one;
- a `.slate` path passed on the command line is imported and opened (double-click
  works once the file association is configured);
- the format, limits and validation rules are described in `docs/FORMAT.md`.

The last 10 snapshots of each local board are kept in SQLite and used for
recovery if the main record is unreadable or empty.

Server data:

- Linux: `~/.local/share/canvasproject/mongo` (local start-up), log
  `mongod.log`; board images under the assets directory.
- Windows: the MongoDB data directory chosen during installation.
- Backup: `mongodump --db canvas_collab --out backup-$(date +%F)`
- Restore: `mongorestore --db canvas_collab backup-.../canvas_collab`

Moving boards between MongoDB and SQLite is done with the migration tool; see
`docs/MIGRATION.md`:

```bash
java -cp java-collab/server/target/canvas-collab-server-1.0.0.jar \
  org.example.canvasproject.migration.MigrationCli mongo sqlite \
  --dir ~/.local/share/canvasproject
```

## Building a portable client

```bash
./package-client.sh      # dist/Slate-1.0-linux.zip
```

The archive contains the jar, libraries and `Slate.sh`; the target machine only
needs JRE 17+. Build for Windows on Windows:
`.\mvnw.cmd -DskipTests package` (JavaFX native libraries are platform-specific).

## Troubleshooting

- **JDK not found** — the system Java may be 11; set
  `JAVA_HOME=/usr/lib/jvm/openjdk17` (or 21).
- **Client cannot connect** — check the address under "Server…", the port and
  the firewall; the server log should show `ws://...`.
- **Empty board list** — boards belong to a device key; check "Device key". A
  lost key cannot be recovered.
- **MongoDB does not start** — inspect
  `~/.local/share/canvasproject/mongod.log` and check whether port 27017 is
  free.
- **TLS error** — a self-signed certificate needs a truststore, or use Caddy.

## Stack and layout

```
src/                     JavaFX client (MVC, Canvas)
slate-core/              shared storage contracts, SQLite, assets, transport
java-collab/server/      WebSocket server, MongoDB, roles and invitations
branding/                Slate logo and alternative variants (SVG + PNG)
docs/                    audit, decisions, progress, protocol, format, migration
run-server.*, run-client.*, package-client.sh
DESIGN_SYSTEM.md         UI palette and components
```

Java 17/21, JavaFX 17, Java-WebSocket 1.5, MongoDB Java Driver 5.1,
sqlite-jdbc, org.json.

## License

MIT — see `LICENSE`.
