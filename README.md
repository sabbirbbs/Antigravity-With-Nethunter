<div align="center">

  <img src="app/src/main/res/drawable-nodpi/ic_launcher_logo.png" alt="Antigravity With NetHunter Logo" width="88" height="88" />

  # Antigravity With NetHunter

  **Full-Power AI Coding Agent for Rooted Android & Kali NetHunter**

  [![Android](https://img.shields.io/badge/Android-11.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://android.com)
  [![Architecture](https://img.shields.io/badge/Architecture-ARM64-blue?style=flat-square)](https://arm.com)
  [![Kali NetHunter](https://img.shields.io/badge/Kali-NetHunter%20Chroot-557C94?style=flat-square&logo=kalilinux&logoColor=white)](https://www.kali.org/get-kali/#kali-mobile)
  [![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
  [![License](https://img.shields.io/badge/License-AGPL_3.0-blue?style=flat-square)](LICENSE)

</div>

**Antigravity With NetHunter** is an enhanced open-source fork of [KuatoDev/Antigravity](https://github.com/KuatoDev/Antigravity) engineered for **rooted Android devices (Magisk)** and **Kali NetHunter** chroot environments. It bridges the Antigravity AI coding agent, interactive terminal, code editor, and live web/file preview directly with your phone's real Linux ecosystem and shared Android storage—without requiring any Android OS modifications.

---

## Key Features

- **Native Kali NetHunter Chroot & Magisk Root Integration (`su -mm`)**
  - Auto-discovers your Kali NetHunter chroot (`/data/local/nhsystem/kali-arm64`, `kalifs`, etc.) and bind-mounts `/proc`, `/sys`, `/dev`, `/dev/pts`, `/dev/shm`, `/storage/emulated/0`, and app data cleanly in the global mount namespace.
  - Switch seamlessly between **Kali NetHunter (`KALI`)**, **Android Root (`ROOT`)**, and **App Sandbox (`SANDBOX`)** from **Settings → Developer & System → Root & Kali NetHunter**.
  - **Zero-Bloat Native Bootstrap**: When Magisk Root + Kali NetHunter chroot are present, setup skips downloading the redundant Termux Linux rootfs (`bootstrap-aarch64.zip`) and routes `bash`, `sh`, `python3`, `git`, `curl`, `tar`, `node`, `npm`, and `pip3` directly into your Kali chroot.
- **In-App Live Web Browser & `file:///` Previewer**
  - Clicking `file:///` links in AI responses or Markdown previews opens files **directly inside Antigravity** (HTML, SVG, Markdown, images, directories, and syntax/line-numbered code with `#L` line jump), with one-tap **Edit in Code Editor**, **Copy Path**, **Copy to Downloads**, and **Open With (`FileProvider`)**.
  - Clicking `http://localhost:...`, `http://127.0.0.1:...`, `http://0.0.0.0:...`, or LAN Wi-Fi URLs (`http://192.168.x.x:...`) opens an **in-app WebView browser** with URL bar, refresh, port diagnostics, and instant switching between `Localhost` and your phone's LAN Wi-Fi IP.
- **Persistent Background Web Server Manager (`agy-srv`)**
  - Run Python, Node/Vite, Next.js, Flask, PHP, or Nginx servers detached via `setsid` so they stay alive across AI turns:
    ```bash
    agy-srv start web "python3 -m http.server 8000 --bind 0.0.0.0"
    agy-srv list
    agy-srv ports
    agy-srv logs web
    agy-srv stop web
    ```
- **Direct Kali Linux & Root File/Folder Picker**
  - Attach files or open/create project workspaces directly inside Kali Linux (`/root`, `/home`, `/var/www`) or `/storage/emulated/0/Antigravity` without Android SAF limitations—plus quick-root chips, Copy Path, and Copy-to-Downloads in the Workspace File Tree.
- **Bengali & Multi-Language Voice Typing**
  - Built-in voice input language switcher (`বাংলা bn-BD`, `EN en-US`, `বাংলা-IN`, `AUTO`) directly on the chat input bar.
- **Subagent Lifecycle & Execution Fixes**
  - Automatically clears completed subagents from the running bar once finished.

---

## Requirements

- **Android 11+** (`arm64-v8a`)
- **All Files Access** permission (for shared `/storage/emulated/0/Antigravity` workspace)
- *(Recommended)* **Magisk Root (`su`)** and **Kali NetHunter** chroot (`/data/local/nhsystem/kali-arm64`)

---

## Building from Source

1. Clone this repository:
   ```bash
   git clone https://github.com/sabbirbbs/Antigravity-With-Nethunter.git
   cd Antigravity-With-Nethunter
   ```
2. Ensure **JDK 17** and **Android SDK (API 36, Build-Tools 35.0.0)** are installed, and configure `local.properties` (git-ignored):
   ```properties
   sdk.dir=/path/to/Android/Sdk
   oauth.clientId=YOUR_GOOGLE_OAUTH_CLIENT_ID
   oauth.clientSecret=YOUR_GOOGLE_OAUTH_CLIENT_SECRET
   ```
3. Build the Debug APK:
   ```bash
   chmod +x gradlew
   ./gradlew assembleDebug
   ```
   Output APK: `app/build/outputs/apk/debug/app-debug.apk`

---

## Quick Setup on Device

1. Install the built APK and grant **All Files Access** and **Magisk Root** when prompted.
2. Open **Settings → Developer & System → Root & Kali NetHunter** and tap **Mount & Sync Kali**.
3. Use **"Select Kali Linux / Root Folder"** in the sidebar to open projects directly inside Kali NetHunter or `/storage/emulated/0/Antigravity`.
4. Use `kali <cmd>` or `nh <cmd>` in the built-in Terminal (or set mode to `KALI` for automatic Kali execution).

---

## Credits & Upstream

- Upstream base project: **[KuatoDev/Antigravity](https://github.com/KuatoDev/Antigravity)**
- **[Kali NetHunter](https://www.kali.org/get-kali/#kali-mobile)** — Mobile penetration testing & Linux chroot ecosystem
- **[Termux](https://github.com/termux)** — Userspace scripts & `libtermux-exec` compatibility
- **[Google DeepMind](https://deepmind.google)** — Antigravity core `language_server`

---

## License

Licensed under the **GNU Affero General Public License v3.0 (AGPL-3.0)**. See [LICENSE](LICENSE) for full details.
