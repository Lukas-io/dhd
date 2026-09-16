# Phone Control and DHD

Phone Control is a local TypeScript MCP server for controlling one authorized Android device through a small, allowlisted operation set. It uses display-scoped screenshots for observation and typed ADB operations for device input. It never exposes an ADB shell to MCP callers.

## What is DHD?

DHD is the phone-side assistant built on top of Phone Control. It gives the user a native Android assistant experience while a desktop companion connects the phone to Codex App Server:

- `apps/dhd-android/` owns the phone UI, app permissions, Wireless Debugging pairing, session state, and typed phone actions.
- `apps/dhd-companion/` runs on the computer, pairs with the phone, and forwards DHD requests to Codex App Server.
- Codex App Server provides the authenticated assistant runtime. DHD uses a separate `%USERPROFILE%\.dhd` home so it does not inherit the desktop Codex workspace.

The phone remains the authority for which apps can be used and which actions are allowed. The desktop side cannot bypass the phone's allowlist, observation checks, or confirmation policy.

## Windows setup

Requirements:

- Node.js 22.13 or newer
- pnpm 11
- Codex CLI on `PATH` (needed by the DHD companion)
- Android platform-tools (`adb`)
- An Android phone with USB debugging or wireless debugging enabled
- JDK 17 and Android SDK API 35 if you are building the DHD APK

From PowerShell:

```powershell
pnpm install
pnpm dhd:setup
pnpm typecheck
pnpm test
```

`pnpm dhd:setup` creates these directories automatically:

- `%USERPROFILE%\.dhd\codex-home` — DHD's isolated Codex configuration and login.
- `%USERPROFILE%\.dhd\codex-runtime` — the App Server working directory.

It first checks the DHD home with `codex login status`. If it is not logged in,
it runs `codex login` with `CODEX_HOME` pointed at that isolated directory. The
normal ChatGPT sign-in flow opens a browser window; use Chrome by making it the
Windows default browser.

For a setup-only run without opening the login flow, use:

```powershell
pnpm dhd:setup -- --skip-login
```

For a headless or remote environment, use device-code login instead:

```powershell
pnpm dhd:setup -- --device-auth
```

The helper is only a convenience wrapper around the Codex CLI;

## Start DHD

The standalone `apps/dhd-android/` app is the phone-side authority. Build and
install it when you are developing the Android app:

```powershell
cd apps/dhd-android
.\gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
cd ../..
```

On the phone, the full pairing instructions are here - 
[`apps/dhd-android/README.md`](apps/dhd-android/README.md). The app is also quite intuitive

The normal development path is now wireless: open the companion dashboard and
refresh the phone list. Select the intended phone, then approve the one-time
request in DHD Settings → Companion connection. The companion learns the
phone's current route and receives its bridge credential only after approval;
there is no code, IP, port, or token to copy. `adb forward` remains available
as a loopback-only fallback for development.

### DHD companion

After `pnpm dhd:setup` has completed, run the companion dashboard:

```powershell
pnpm companion:dashboard
```

The dashboard starts its worker automatically, pairs with a selected phone on
the local network, and shows local activity. The discovered connection is saved
locally so the companion can rediscover the phone when its local network
address changes.

For a direct worker run, use `pnpm companion:worker`. The companion passes
`CODEX_HOME` only to its App Server child, so no manual environment override is
needed for normal DHD use. Set
`PHONE_ASSISTANT_CODEX_HOME` to use another authenticated home, or
`PHONE_ASSISTANT_CODEX_CWD` to use another runtime directory. The
`codex:app-server` script starts Codex directly and does not apply this DHD
child-process wiring.

See the [official Codex authentication documentation](https://learn.chatgpt.com/docs/auth)
for the browser and device-code login flows.
