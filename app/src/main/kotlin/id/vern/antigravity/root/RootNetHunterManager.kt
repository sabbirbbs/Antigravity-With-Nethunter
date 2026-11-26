package id.vern.antigravity.root

import android.content.Context
import android.os.Environment
import android.system.Os
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

enum class ExecutionMode(val displayName: String, val shortBadge: String) {
    SANDBOX("App Sandbox (Termux)", "SANDBOX"),
    ROOT_ANDROID("Android Root (su)", "ROOT"),
    KALI_NETHUNTER("Kali NetHunter (Root + Chroot)", "KALI")
}

data class RootFileEntry(
    val name: String,
    val absolutePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long
)

data class NetHunterEnvStatus(
    val hasRoot: Boolean = false,
    val rootUidInfo: String = "",
    val chrootPath: String? = null,
    val isChrootMounted: Boolean = false,
    val availableChroots: List<String> = emptyList(),
    val effectiveMode: ExecutionMode = ExecutionMode.SANDBOX
)

object RootNetHunterManager {
    private const val TAG = "RootNetHunterManager"
    private const val PREFS_NAME = "antigravity_prefs"
    private const val KEY_EXEC_MODE = "ag_exec_mode"
    private const val KEY_CHROOT_PATH = "ag_nh_chroot_path"
    private const val KEY_DEFAULT_WS_INIT = "ag_default_ws_init"
    private const val KEY_VOICE_LANG = "ag_voice_lang"

    private val CANDIDATE_CHROOT_BASES = listOf(
        "/data/local/nhsystem/kali-arm64",
        "/data/local/nhsystem/kalifs",
        "/data/local/nhsystem/kali-arm64-full",
        "/data/local/nhsystem/kali-arm64-minimal",
        "/data/local/nhsystem/kali-armhf"
    )

    @Volatile
    var cachedStatus: NetHunterEnvStatus? = null
        private set

    fun getVoiceLanguage(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VOICE_LANG, "bn-BD") ?: "bn-BD"
    }

    fun setVoiceLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_VOICE_LANG, lang.trim()).apply()
    }

    fun getVoiceLanguageBadge(lang: String): String = when (lang) {
        "bn-BD" -> "বাংলা"
        "bn-IN" -> "বাংলা-IN"
        "en-US" -> "EN"
        else -> "AUTO"
    }

    fun getDefaultWorkspaceDir(): File {
        val sdcard = Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
        return File(sdcard, "Antigravity")
    }

    fun ensureDefaultWorkspace(context: Context): File {
        val dir = getDefaultWorkspaceDir()
        if (!dir.exists()) {
            val created = dir.mkdirs()
            if (!created) {
                runSuQuick("mkdir -p '${shellEscape(dir.absolutePath)}' && chmod 0777 '${shellEscape(dir.absolutePath)}'")
            }
        }
        return dir
    }

    fun getSavedExecutionMode(context: Context): ExecutionMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_EXEC_MODE, null)
        if (raw != null) {
            return try { ExecutionMode.valueOf(raw) } catch (_: Exception) { ExecutionMode.SANDBOX }
        }
        // Auto-select best mode if not explicitly configured yet
        val status = cachedStatus ?: refreshStatus(context)
        return when {
            status.hasRoot && !status.chrootPath.isNullOrBlank() -> ExecutionMode.KALI_NETHUNTER
            status.hasRoot -> ExecutionMode.ROOT_ANDROID
            else -> ExecutionMode.SANDBOX
        }
    }

    fun setExecutionMode(context: Context, mode: ExecutionMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_EXEC_MODE, mode.name).apply()
        cachedStatus = cachedStatus?.copy(effectiveMode = getEffectiveExecutionMode(context))
    }

    fun getSavedChrootPath(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CHROOT_PATH, null)?.trim()?.takeIf { it.isNotBlank() }
    }

    fun setSavedChrootPath(context: Context, path: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CHROOT_PATH, path.trim()).apply()
        refreshStatus(context)
    }

    fun hasInitializedDefaultWorkspace(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEFAULT_WS_INIT, false)
    }

    fun markDefaultWorkspaceInitialized(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEFAULT_WS_INIT, true).apply()
    }

    fun getEffectiveExecutionMode(context: Context): ExecutionMode {
        val desired = getSavedExecutionMode(context)
        val status = cachedStatus ?: refreshStatus(context)
        return when (desired) {
            ExecutionMode.KALI_NETHUNTER -> when {
                status.hasRoot && !status.chrootPath.isNullOrBlank() -> ExecutionMode.KALI_NETHUNTER
                status.hasRoot -> ExecutionMode.ROOT_ANDROID
                else -> ExecutionMode.SANDBOX
            }
            ExecutionMode.ROOT_ANDROID -> if (status.hasRoot) ExecutionMode.ROOT_ANDROID else ExecutionMode.SANDBOX
            ExecutionMode.SANDBOX -> ExecutionMode.SANDBOX
        }
    }

    fun shellEscape(value: String): String = value.replace("'", "'\\''")

    fun runSuQuick(command: String, timeoutMs: Long = 4000L): Pair<Int, String> {
        return try {
            val pb = ProcessBuilder("su", "-mm", "-c", command)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = StringBuffer()
            val readerThread = Thread {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { br ->
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            if (out.isNotEmpty()) out.append('\n')
                            out.append(line)
                        }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true }
            readerThread.start()
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                try { proc.outputStream.close() } catch (_: Exception) {}
                try { proc.inputStream.close() } catch (_: Exception) {}
                try { proc.destroyForcibly() } catch (_: Exception) {}
                readerThread.join(300)
                return Pair(-1, "Timed out")
            }
            readerThread.join(500)
            Pair(proc.exitValue(), out.toString().trim())
        } catch (e: Exception) {
            Pair(-1, e.message ?: "su failed")
        }
    }

    fun refreshStatus(context: Context): NetHunterEnvStatus {
        val (idExit, idOut) = runSuQuick("id", 3500L)
        val hasRoot = idExit == 0 && idOut.contains("uid=0")
        if (!hasRoot) {
            val status = NetHunterEnvStatus(
                hasRoot = false,
                rootUidInfo = idOut,
                effectiveMode = ExecutionMode.SANDBOX
            )
            cachedStatus = status
            return status
        }

        val probeScript = buildString {
            append("for d in ")
            CANDIDATE_CHROOT_BASES.forEach { append("'").append(it).append("' ") }
            append("/data/local/nhsystem/kali* /data/local/kali*; do ")
            append("if [ -d \"\$d\" ] && { [ -x \"\$d/bin/bash\" ] || [ -x \"\$d/usr/bin/env\" ]; }; then echo \"CHROOT:\$d\"; fi; ")
            append("done")
        }
        val (_, probeOut) = runSuQuick(probeScript, 4000L)
        val discovered = probeOut.lines()
            .filter { it.startsWith("CHROOT:") }
            .map { it.removePrefix("CHROOT:").trim() }
            .distinct()

        val savedCustom = getSavedChrootPath(context)
        val activeChroot = when {
            !savedCustom.isNullOrBlank() && discovered.contains(savedCustom) -> savedCustom
            !savedCustom.isNullOrBlank() -> {
                val (cExit, _) = runSuQuick("[ -d '${shellEscape(savedCustom)}' ] && [ -x '${shellEscape(savedCustom)}/bin/bash' -o -x '${shellEscape(savedCustom)}/usr/bin/env' ]")
                if (cExit == 0) savedCustom else discovered.firstOrNull()
            }
            else -> discovered.firstOrNull()
        }

        val isMounted = if (!activeChroot.isNullOrBlank()) {
            val esc = shellEscape(activeChroot)
            val (mExit, _) = runSuQuick("grep -qs ' $esc/proc ' /proc/mounts && grep -qs ' $esc/sdcard ' /proc/mounts")
            mExit == 0
        } else false

        val desired = run {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_EXEC_MODE, null)
            if (raw != null) {
                try { ExecutionMode.valueOf(raw) } catch (_: Exception) { ExecutionMode.KALI_NETHUNTER }
            } else {
                if (activeChroot != null) ExecutionMode.KALI_NETHUNTER else ExecutionMode.ROOT_ANDROID
            }
        }
        val effective = when (desired) {
            ExecutionMode.KALI_NETHUNTER -> if (activeChroot != null) ExecutionMode.KALI_NETHUNTER else ExecutionMode.ROOT_ANDROID
            ExecutionMode.ROOT_ANDROID -> ExecutionMode.ROOT_ANDROID
            ExecutionMode.SANDBOX -> ExecutionMode.SANDBOX
        }

        val status = NetHunterEnvStatus(
            hasRoot = true,
            rootUidInfo = idOut,
            chrootPath = activeChroot,
            isChrootMounted = isMounted,
            availableChroots = discovered,
            effectiveMode = effective
        )
        cachedStatus = status
        return status
    }

    fun ensureChrootMounted(context: Context, chrootPath: String, workspaceDir: File? = null): Boolean {
        val escChroot = shellEscape(chrootPath)
        val escFiles = shellEscape(context.filesDir.absolutePath)
        val wsPath = workspaceDir?.absolutePath ?: getDefaultWorkspaceDir().absolutePath
        val escWs = shellEscape(wsPath)

        val mountCmd = """
            set -e
            CHROOT='$escChroot'
            [ -n "${'$'}CHROOT" ] && [ "${'$'}CHROOT" != "/" ] && [ -d "${'$'}CHROOT" ] || exit 1
            [ -f "${'$'}CHROOT/etc/kali_version" ] || [ -f "${'$'}CHROOT/etc/debian_version" ] || [ -x "${'$'}CHROOT/bin/bash" ] || exit 1
            mkdir -p "${'$'}CHROOT/proc" "${'$'}CHROOT/sys" "${'$'}CHROOT/dev" "${'$'}CHROOT/dev/pts" "${'$'}CHROOT/dev/shm"
            mkdir -p "${'$'}CHROOT/sdcard" "${'$'}CHROOT/storage/emulated/0" "${'$'}CHROOT/opt/antigravity" "${'$'}CHROOT/root"
            mkdir -p '$escWs'

            is_mounted() {
                grep -qs " ${'$'}1 " /proc/mounts
            }

            is_mounted "${'$'}CHROOT/proc" || mount -t proc proc "${'$'}CHROOT/proc"
            is_mounted "${'$'}CHROOT/sys" || mount -t sysfs sysfs "${'$'}CHROOT/sys"
            is_mounted "${'$'}CHROOT/dev" || mount -o bind /dev "${'$'}CHROOT/dev"
            is_mounted "${'$'}CHROOT/dev/pts" || mount -t devpts devpts "${'$'}CHROOT/dev/pts" -o gid=5,mode=620 || mount -o bind /dev/pts "${'$'}CHROOT/dev/pts" || true
            is_mounted "${'$'}CHROOT/dev/shm" || mount -t tmpfs -o size=256M tmpfs "${'$'}CHROOT/dev/shm" || true

            STORAGE_SRC="/data/media/0"
            [ -d "/storage/emulated/0" ] && STORAGE_SRC="/storage/emulated/0"
            is_mounted "${'$'}CHROOT/sdcard" || mount -o bind "${'$'}STORAGE_SRC" "${'$'}CHROOT/sdcard" || true
            is_mounted "${'$'}CHROOT/storage/emulated/0" || mount -o bind "${'$'}STORAGE_SRC" "${'$'}CHROOT/storage/emulated/0" || true
            if [ ! -x "${'$'}CHROOT/opt/antigravity/usr/bin/agy-srv" ]; then
                umount -l "${'$'}CHROOT/opt/antigravity" 2>/dev/null || true
                mkdir -p "${'$'}CHROOT/opt/antigravity" 2>/dev/null || true
                mount -o bind '$escFiles' "${'$'}CHROOT/opt/antigravity" || true
            fi
            if [ ! -e "${'$'}CHROOT$escFiles/engine/bin/agy" ]; then
                mkdir -p "${'$'}CHROOT$escFiles" 2>/dev/null || true
                mount -o bind '$escFiles' "${'$'}CHROOT$escFiles" || true
            fi

            ln -sfn /sdcard/Antigravity "${'$'}CHROOT/root/workspace" 2>/dev/null || true
            if [ -f "${'$'}CHROOT/etc/kali_version" ] || [ -f "${'$'}CHROOT/etc/debian_version" ]; then
                mkdir -p "${'$'}CHROOT/system/bin" 2>/dev/null || true
                ln -sfn /bin/sh "${'$'}CHROOT/system/bin/sh" 2>/dev/null || true
            fi

            # Guard host /system/bin/sh against circular symlink loops
            if [ "${'$'}(readlink /system/bin/sh 2>/dev/null)" = "/bin/sh" ] || [ "${'$'}(readlink /system/bin/sh 2>/dev/null)" = "sh" ] || ! /system/bin/sh -c 'true' 2>/dev/null; then
                for bb in /system/bin/busybox_nh-1.32 /system/bin/busybox_nh-1.38.0 /system/bin/busybox_nh /system/bin/mksh /system/bin/toybox; do
                    if [ -x "${'$'}bb" ]; then
                        ln -sfn "${'$'}bb" /system/bin/sh 2>/dev/null || true
                        break
                    fi
                done
            fi

            # Deploy Android host bridge inside Kali NetHunter
            mkdir -p "${'$'}CHROOT/usr/local/bin" 2>/dev/null || true
            cat << 'EOF' > "${'$'}CHROOT/usr/local/bin/android"
#!/bin/bash
if [ "${'$'}#" -eq 0 ]; then
    exec nsenter -t 1 -m /system/bin/sh
else
    exec nsenter -t 1 -m /system/bin/sh -c "${'$'}*"
fi
EOF
            chmod 755 "${'$'}CHROOT/usr/local/bin/android" 2>/dev/null || true
            cp -f "${'$'}CHROOT/usr/local/bin/android" "${'$'}CHROOT/usr/local/bin/android-sh" 2>/dev/null || true

            if [ ! -s "${'$'}CHROOT/etc/resolv.conf" ] || ! grep -q "nameserver" "${'$'}CHROOT/etc/resolv.conf" 2>/dev/null; then
                printf "nameserver 8.8.8.8\nnameserver 1.1.1.1\n" > "${'$'}CHROOT/etc/resolv.conf"
            fi
            if [ ! -s "${'$'}CHROOT/etc/hosts" ]; then
                printf "127.0.0.1 localhost\n::1 localhost ip6-localhost\n" > "${'$'}CHROOT/etc/hosts"
            fi
            chmod 1777 "${'$'}CHROOT/tmp" 2>/dev/null || true
        """.trimIndent()

        val (exit, out) = runSuQuick(mountCmd, 8000L)
        if (exit != 0) {
            Log.w(TAG, "ensureChrootMounted failed ($exit): $out")
        }
        return exit == 0
    }

    fun translatePathForChroot(androidPath: String, chrootPath: String): String {
        val clean = androidPath.trim().ifBlank { "/sdcard/Antigravity" }
        return when {
            clean == chrootPath || clean.startsWith("$chrootPath/") -> {
                val rel = clean.removePrefix(chrootPath)
                if (rel.isBlank()) "/" else rel
            }
            clean.startsWith("/storage/emulated/0") -> clean
            clean.startsWith("/sdcard") -> clean
            clean.startsWith("/data/media/0") -> "/sdcard" + clean.removePrefix("/data/media/0")
            else -> "/sdcard/Antigravity"
        }
    }

    fun buildChrootInvocation(
        chrootPath: String,
        command: String,
        workingDirAndroid: String? = null
    ): String {
        val escChroot = shellEscape(chrootPath)
        val chrootCwd = translatePathForChroot(workingDirAndroid ?: "/sdcard/Antigravity", chrootPath)
        val escCwd = shellEscape(chrootCwd)
        val escCmd = shellEscape(command)
        return "unset LD_PRELOAD LD_LIBRARY_PATH; exec chroot '$escChroot' /usr/bin/env -i " +
            "HOME=/root TERM=xterm-256color LANG=C.UTF-8 " +
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/antigravity/usr/bin " +
            "DEBIAN_FRONTEND=noninteractive " +
            "/bin/bash -c 'cd \"\$1\" 2>/dev/null || cd /root; eval \"\$2\"' _ '$escCwd' '$escCmd'"
    }

    fun deployKaliNativeShims(context: Context, chrootPath: String) {
        val filesDir = context.filesDir
        val prefix = File(filesDir, "usr")
        val binDir = File(prefix, "bin").apply { mkdirs() }
        val escChroot = shellEscape(chrootPath)

        val tlsDir = File(prefix, "etc/tls").apply { mkdirs() }
        val certDest = File(tlsDir, "cert.pem")
        if (!certDest.exists() || certDest.length() < 1024L) {
            runSuQuick("if [ -s '$escChroot/etc/ssl/certs/ca-certificates.crt' ]; then cp -f '$escChroot/etc/ssl/certs/ca-certificates.crt' '${shellEscape(certDest.absolutePath)}' && chmod 0644 '${shellEscape(certDest.absolutePath)}'; fi")
        }

        val tools = listOf("bash", "sh", "python3", "python", "tar", "git", "curl", "node", "npm", "pip3")
        for (tool in tools) {
            val target = File(binDir, tool)
            val isExistingRealBinary = target.exists() && target.length() > 8192L
            if (!isExistingRealBinary) {
                val shim = """
                    #!/system/bin/sh
                    if [ -f /etc/kali_version ] || [ -f /etc/debian_version ]; then
                        if [ -x "/usr/bin/$tool" ]; then exec "/usr/bin/$tool" "${'$'}@"; fi
                        if [ -x "/bin/$tool" ]; then exec "/bin/$tool" "${'$'}@"; fi
                    fi
                    CHROOT="${'$'}{AGY_CHROOT_DIR:-$chrootPath}"
                    CUR_DIR="${'$'}(pwd 2>/dev/null || echo '/sdcard/Antigravity')"
                    case "${'$'}CUR_DIR" in
                        "${'$'}CHROOT"*) CHROOT_CWD="${'$'}{CUR_DIR#"${'$'}CHROOT"}"; [ -z "${'$'}CHROOT_CWD" ] && CHROOT_CWD="/" ;;
                        /storage/emulated/0*|/sdcard*) CHROOT_CWD="${'$'}CUR_DIR" ;;
                        *) CHROOT_CWD="/sdcard/Antigravity" ;;
                    esac
                    if [ "${'$'}(id -u)" = "0" ] && [ -n "${'$'}CHROOT" ] && [ "${'$'}CHROOT" != "/" ] && [ -d "${'$'}CHROOT" ]; then
                        unset LD_PRELOAD LD_LIBRARY_PATH
                        [ -e "${'$'}CHROOT/proc/uptime" ] || mount -t proc proc "${'$'}CHROOT/proc" 2>/dev/null || true
                        grep -qs " ${'$'}CHROOT/dev " /proc/mounts || [ -c "${'$'}CHROOT/dev/null" ] || mount -o bind /dev "${'$'}CHROOT/dev" 2>/dev/null || true
                        grep -qs " ${'$'}CHROOT/dev/pts " /proc/mounts || mount -t devpts devpts "${'$'}CHROOT/dev/pts" 2>/dev/null || true
                        [ -d "${'$'}CHROOT/sys/kernel" ] || mount -t sysfs sysfs "${'$'}CHROOT/sys" 2>/dev/null || true
                        [ -d "${'$'}CHROOT/sdcard/Android" ] || { mkdir -p "${'$'}CHROOT/sdcard" 2>/dev/null || true; mount -o bind /storage/emulated/0 "${'$'}CHROOT/sdcard" 2>/dev/null || true; }
                        [ -d "${'$'}CHROOT/storage/emulated/0/Android" ] || { mkdir -p "${'$'}CHROOT/storage/emulated/0" 2>/dev/null || true; mount -o bind /storage/emulated/0 "${'$'}CHROOT/storage/emulated/0" 2>/dev/null || true; }
                        [ -x "${'$'}CHROOT/opt/antigravity/usr/bin/agy-srv" ] || { umount -l "${'$'}CHROOT/opt/antigravity" 2>/dev/null || true; mkdir -p "${'$'}CHROOT/opt/antigravity" 2>/dev/null || true; mount -o bind '${filesDir.absolutePath}' "${'$'}CHROOT/opt/antigravity" 2>/dev/null || true; }
                        if [ -f "${'$'}CHROOT/etc/kali_version" ] || [ -f "${'$'}CHROOT/etc/debian_version" ]; then
                            [ -e "${'$'}CHROOT/system/bin/sh" ] || { mkdir -p "${'$'}CHROOT/system/bin" 2>/dev/null || true; ln -sfn /bin/sh "${'$'}CHROOT/system/bin/sh" 2>/dev/null || true; }
                        fi
                        exec chroot "${'$'}CHROOT" /usr/bin/env -i HOME=/root TERM=xterm-256color LANG=C.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/antigravity/usr/bin DEBIAN_FRONTEND=noninteractive /bin/bash -c 'cd "${'$'}1" 2>/dev/null || cd /root; shift; exec "${'$'}0" "${'$'}@"' "$tool" "${'$'}CHROOT_CWD" "${'$'}@"
                    fi
                    CHROOT_ESC=${'$'}(printf '%s' "${'$'}CHROOT" | sed "s/'/'\\\\''/g")
                    CWD_ESC=${'$'}(printf '%s' "${'$'}CHROOT_CWD" | sed "s/'/'\\\\''/g")
                    ARGS=""
                    for a in "${'$'}@"; do
                        ESC=${'$'}(printf '%s' "${'$'}a" | sed "s/'/'\\\\''/g")
                        ARGS="${'$'}ARGS '${'$'}ESC'"
                    done
                    SU_BIN="su"
                    for s in /system_ext/bin/su /system_ext/xbin/su /debug_ramdisk/su /sbin/su /system/xbin/su /system/bin/su; do
                        if [ -x "${'$'}s" ]; then SU_BIN="${'$'}s"; break; fi
                    done
                    exec "${'$'}SU_BIN" -mm -c "unset LD_PRELOAD LD_LIBRARY_PATH; exec chroot '${'$'}CHROOT_ESC' /usr/bin/env -i HOME=/root TERM=xterm-256color LANG=C.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/antigravity/usr/bin DEBIAN_FRONTEND=noninteractive /bin/bash -c 'cd \"\$1\" 2>/dev/null || cd /root; shift; exec \"\$0\" \"\$@\"' '$tool' '${'$'}CWD_ESC'${'$'}ARGS"
                """.trimIndent() + "\n"
                writeExecutableScript(target, shim)
            }
        }

        runSuQuick("""
            mkdir -p '$escChroot/usr/local/bin' 2>/dev/null || true
            cat << 'EOF' > '$escChroot/usr/local/bin/android'
#!/bin/bash
if [ "${'$'}#" -eq 0 ]; then
    exec nsenter -t 1 -m /system/bin/sh
else
    exec nsenter -t 1 -m /system/bin/sh -c "${'$'}*"
fi
EOF
            chmod 755 '$escChroot/usr/local/bin/android' 2>/dev/null || true
            cp -f '$escChroot/usr/local/bin/android' '$escChroot/usr/local/bin/android-sh' 2>/dev/null || true
        """.trimIndent(), 4000L)
    }

    fun deployRootAndNetHunterTools(context: Context) {
        val filesDir = context.filesDir
        val prefix = File(filesDir, "usr")
        val binDir = File(prefix, "bin").apply { mkdirs() }
        val status = cachedStatus ?: refreshStatus(context)
        val chrootPath = status.chrootPath ?: "/data/local/nhsystem/kali-arm64"
        val wsDefault = getDefaultWorkspaceDir().absolutePath

        val kaliScript = """
            #!/system/bin/sh
            if [ -f /etc/kali_version ] || [ -f /etc/debian_version ]; then
                if [ "${'$'}#" -eq 0 ]; then
                    exec /bin/bash -l
                else
                    exec /bin/bash -c "$*"
                fi
            fi
            CHROOT="${'$'}{AGY_CHROOT_DIR:-$chrootPath}"
            if [ ! -d "${'$'}CHROOT" ]; then
                for d in /data/local/nhsystem/kali-arm64 /data/local/nhsystem/kalifs /data/local/nhsystem/kali*; do
                    if [ -d "${'$'}d" ] && { [ -x "${'$'}d/bin/bash" ] || [ -x "${'$'}d/usr/bin/env" ]; }; then
                        CHROOT="${'$'}d"
                        break
                    fi
                done
            fi
            if [ -z "${'$'}CHROOT" ] || [ "${'$'}CHROOT" = "/" ] || [ ! -d "${'$'}CHROOT" ]; then
                echo "[kali] Error: Kali NetHunter chroot not found." >&2
                exit 1
            fi
            CUR_DIR="${'$'}(pwd 2>/dev/null || echo '/sdcard/Antigravity')"
            case "${'$'}CUR_DIR" in
                "${'$'}CHROOT"*) CHROOT_CWD="${'$'}{CUR_DIR#"${'$'}CHROOT"}"; [ -z "${'$'}CHROOT_CWD" ] && CHROOT_CWD="/" ;;
                /storage/emulated/0*|/sdcard*) CHROOT_CWD="${'$'}CUR_DIR" ;;
                *) CHROOT_CWD="/sdcard/Antigravity" ;;
            esac
            MOUNT_PREP="unset LD_PRELOAD LD_LIBRARY_PATH; [ -n '${'$'}CHROOT' ] && [ '${'$'}CHROOT' != '/' ] && { mkdir -p '${'$'}CHROOT/sdcard' '${'$'}CHROOT/storage/emulated/0' '${'$'}CHROOT/opt/antigravity'; if [ -f '${'$'}CHROOT/etc/kali_version' ] || [ -f '${'$'}CHROOT/etc/debian_version' ]; then mkdir -p '${'$'}CHROOT/system/bin'; ln -sfn /bin/sh '${'$'}CHROOT/system/bin/sh' 2>/dev/null || true; fi; grep -qs ' ${'$'}CHROOT/proc ' /proc/mounts || mount -t proc proc '${'$'}CHROOT/proc'; grep -qs ' ${'$'}CHROOT/sys ' /proc/mounts || mount -t sysfs sysfs '${'$'}CHROOT/sys'; grep -qs ' ${'$'}CHROOT/dev ' /proc/mounts || mount -o bind /dev '${'$'}CHROOT/dev'; grep -qs ' ${'$'}CHROOT/dev/pts ' /proc/mounts || mount -t devpts devpts '${'$'}CHROOT/dev/pts' 2>/dev/null || true; grep -qs ' ${'$'}CHROOT/sdcard ' /proc/mounts || mount -o bind /storage/emulated/0 '${'$'}CHROOT/sdcard' 2>/dev/null || true; grep -qs ' ${'$'}CHROOT/storage/emulated/0 ' /proc/mounts || mount -o bind /storage/emulated/0 '${'$'}CHROOT/storage/emulated/0' 2>/dev/null || true; [ -x '${'$'}CHROOT/opt/antigravity/usr/bin/agy-srv' ] || { umount -l '${'$'}CHROOT/opt/antigravity' 2>/dev/null || true; mount -o bind '${filesDir.absolutePath}' '${'$'}CHROOT/opt/antigravity' 2>/dev/null || true; }; };"
            if [ "${'$'}#" -eq 0 ]; then
                RUN_LINE="${'$'}MOUNT_PREP exec chroot '${'$'}CHROOT' /usr/bin/env -i HOME=/root TERM=xterm-256color LANG=C.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/antigravity/usr/bin /bin/bash -l"
            else
                CMD="$*"
                ESC_CMD=${'$'}(printf '%s' "${'$'}CMD" | sed "s/'/'\\\\''/g")
                RUN_LINE="${'$'}MOUNT_PREP exec chroot '${'$'}CHROOT' /usr/bin/env -i HOME=/root TERM=xterm-256color LANG=C.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/antigravity/usr/bin /bin/bash -c 'cd \"${'$'}1\" 2>/dev/null || cd /root; eval \"${'$'}2\"' _ '${'$'}CHROOT_CWD' '${'$'}ESC_CMD'"
            fi
            if [ "${'$'}(id -u)" = "0" ]; then
                exec /system/bin/sh -c "${'$'}RUN_LINE"
            fi
            SU_BIN="su"
            for s in /system_ext/bin/su /system_ext/xbin/su /debug_ramdisk/su /sbin/su /system/xbin/su /system/bin/su; do
                if [ -x "${'$'}s" ]; then SU_BIN="${'$'}s"; break; fi
            done
            exec "${'$'}SU_BIN" -mm -c "${'$'}RUN_LINE"
        """.trimIndent() + "\n"

        val nhShellScript = """
            #!/system/bin/sh
            unset LD_PRELOAD LD_LIBRARY_PATH
            MODE="${'$'}{AGY_EXEC_MODE:-ROOT_ANDROID}"
            CHROOT="${'$'}{AGY_CHROOT_DIR:-$chrootPath}"
            ARG_CMD=""
            case "${'$'}1" in
                -c|-lc|-ic|-ilc)
                    shift
                    ARG_CMD="${'$'}1"
                    ;;
                *)
                    ARG_CMD="$*"
                    ;;
            esac

            if [ -z "${'$'}ARG_CMD" ]; then
                if [ "${'$'}MODE" = "KALI_NETHUNTER" ] && [ -n "${'$'}CHROOT" ] && [ "${'$'}CHROOT" != "/" ] && [ -d "${'$'}CHROOT" ]; then
                    if [ "${'$'}(id -u)" = "0" ]; then
                        [ -e "${'$'}CHROOT/proc/uptime" ] || mount -t proc proc "${'$'}CHROOT/proc" 2>/dev/null || true
                        grep -qs " ${'$'}CHROOT/dev " /proc/mounts || [ -c "${'$'}CHROOT/dev/null" ] || mount -o bind /dev "${'$'}CHROOT/dev" 2>/dev/null || true
                        grep -qs " ${'$'}CHROOT/dev/pts " /proc/mounts || mount -t devpts devpts "${'$'}CHROOT/dev/pts" 2>/dev/null || true
                        [ -d "${'$'}CHROOT/sdcard/Android" ] || { mkdir -p "${'$'}CHROOT/sdcard" 2>/dev/null || true; mount -o bind /storage/emulated/0 "${'$'}CHROOT/sdcard" 2>/dev/null || true; }
                        [ -x "${'$'}CHROOT/opt/antigravity/usr/bin/agy-srv" ] || { umount -l "${'$'}CHROOT/opt/antigravity" 2>/dev/null || true; mkdir -p "${'$'}CHROOT/opt/antigravity" 2>/dev/null || true; mount -o bind '${filesDir.absolutePath}' "${'$'}CHROOT/opt/antigravity" 2>/dev/null || true; }
                        exec chroot "${'$'}CHROOT" /usr/bin/env -i HOME=/root TERM=xterm-256color LANG=C.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/antigravity/usr/bin /bin/bash -l
                    fi
                fi
                exec "${prefix.absolutePath}/bin/bash"
            fi

            case "${'$'}ARG_CMD" in
                *"${filesDir.absolutePath}/usr/bin/agy-daemon"*|*"${filesDir.absolutePath}/usr/bin/pkg"*)
                    exec /system/bin/sh -c "${'$'}ARG_CMD"
                    ;;
            esac

            if [ "${'$'}MODE" = "KALI_NETHUNTER" ] && [ -n "${'$'}CHROOT" ] && [ "${'$'}CHROOT" != "/" ] && [ -d "${'$'}CHROOT" ]; then
                CUR_DIR="${'$'}(pwd 2>/dev/null || echo '$wsDefault')"
                case "${'$'}CUR_DIR" in
                    "${'$'}CHROOT"*) CHROOT_CWD="${'$'}{CUR_DIR#"${'$'}CHROOT"}"; [ -z "${'$'}CHROOT_CWD" ] && CHROOT_CWD="/" ;;
                    /storage/emulated/0*|/sdcard*) CHROOT_CWD="${'$'}CUR_DIR" ;;
                    *) CHROOT_CWD="$wsDefault" ;;
                esac
                if [ "${'$'}(id -u)" = "0" ]; then
                    [ -e "${'$'}CHROOT/proc/uptime" ] || mount -t proc proc "${'$'}CHROOT/proc" 2>/dev/null || true
                    grep -qs " ${'$'}CHROOT/dev " /proc/mounts || [ -c "${'$'}CHROOT/dev/null" ] || mount -o bind /dev "${'$'}CHROOT/dev" 2>/dev/null || true
                    grep -qs " ${'$'}CHROOT/dev/pts " /proc/mounts || mount -t devpts devpts "${'$'}CHROOT/dev/pts" 2>/dev/null || true
                    [ -d "${'$'}CHROOT/sdcard/Android" ] || { mkdir -p "${'$'}CHROOT/sdcard" 2>/dev/null || true; mount -o bind /storage/emulated/0 "${'$'}CHROOT/sdcard" 2>/dev/null || true; }
                    [ -x "${'$'}CHROOT/opt/antigravity/usr/bin/agy-srv" ] || { umount -l "${'$'}CHROOT/opt/antigravity" 2>/dev/null || true; mkdir -p "${'$'}CHROOT/opt/antigravity" 2>/dev/null || true; mount -o bind '${filesDir.absolutePath}' "${'$'}CHROOT/opt/antigravity" 2>/dev/null || true; }
                    if [ -f "${'$'}CHROOT/etc/kali_version" ] || [ -f "${'$'}CHROOT/etc/debian_version" ]; then
                        [ -e "${'$'}CHROOT/system/bin/sh" ] || { mkdir -p "${'$'}CHROOT/system/bin" 2>/dev/null || true; ln -sfn /bin/sh "${'$'}CHROOT/system/bin/sh" 2>/dev/null || true; }
                    fi
                    exec chroot "${'$'}CHROOT" /usr/bin/env -i HOME=/root TERM=xterm-256color LANG=C.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/antigravity/usr/bin DEBIAN_FRONTEND=noninteractive /bin/bash -c 'cd "$1" 2>/dev/null || cd /root; exec /bin/bash -c "$2"' _ "${'$'}CHROOT_CWD" "${'$'}ARG_CMD"
                fi
                exec "${prefix.absolutePath}/bin/bash" -c "${'$'}ARG_CMD"
            fi

            if [ -x "${prefix.absolutePath}/bin/bash" ]; then
                exec "${prefix.absolutePath}/bin/bash" -c "${'$'}ARG_CMD"
            fi
            exec /system/bin/sh -c "${'$'}ARG_CMD"
        """.trimIndent() + "\n"

        val srvScript = """
            #!/system/bin/sh
            if [ -d "${filesDir.absolutePath}" ]; then
                SRV_DIR="${filesDir.absolutePath}/srv"
            else
                SRV_DIR="/opt/antigravity/srv"
            fi
            mkdir -p "${'$'}SRV_DIR"
            ACTION="${'$'}1"
            shift 2>/dev/null || true

            run_priv() {
                if [ "${'$'}(id -u)" = "0" ]; then
                    /system/bin/sh -c "${'$'}1" 2>/dev/null || /bin/sh -c "${'$'}1"
                else
                    SU_BIN="su"
                    for s in /system_ext/bin/su /system_ext/xbin/su /debug_ramdisk/su /sbin/su /system/xbin/su /system/bin/su; do
                        if [ -x "${'$'}s" ]; then SU_BIN="${'$'}s"; break; fi
                    done
                    "${'$'}SU_BIN" -mm -c "${'$'}1"
                fi
            }

            case "${'$'}ACTION" in
                start)
                    NAME="${'$'}1"
                    shift 2>/dev/null || true
                    if [ -z "${'$'}NAME" ] || [ "${'$'}#" -eq 0 ]; then
                        echo "Usage: agy-srv start <name> <command...>"
                        exit 1
                    fi
                    CMD="$*"
                    LOG="${'$'}SRV_DIR/${'$'}NAME.log"
                    PIDF="${'$'}SRV_DIR/${'$'}NAME.pid"
                    if [ -f "${'$'}PIDF" ] && run_priv "kill -0 '${'$'}(cat "${'$'}PIDF")' 2>/dev/null"; then
                        echo "[agy-srv] '${'$'}NAME' already running (PID ${'$'}(cat "${'$'}PIDF"))"
                        exit 0
                    fi
                    CUR_DIR="${'$'}(pwd 2>/dev/null || echo '$wsDefault')"
                    CHROOT="${'$'}{AGY_CHROOT_DIR:-$chrootPath}"
                    ESC_CMD=${'$'}(printf '%s' "${'$'}CMD" | sed "s/'/'\\\\''/g")

                    if [ -f /etc/kali_version ] || [ -f /etc/debian_version ] || [ ! -d "${'$'}CHROOT" ]; then
                        # Already inside Kali NetHunter chroot OR chroot unavailable: run detached directly
                        ESC_CUR=${'$'}(printf '%s' "${'$'}CUR_DIR" | sed "s/'/'\\\\''/g")
                        run_priv "setsid -w /bin/bash -c 'cd \"\${'$'}1\" 2>/dev/null || cd /root; eval \"\${'$'}2\"' _ '${'$'}ESC_CUR' '${'$'}ESC_CMD' >'${'$'}LOG' 2>&1 </dev/null & echo \${'$'}! >'${'$'}PIDF'"
                    elif [ "${'$'}{AGY_EXEC_MODE:-KALI_NETHUNTER}" = "KALI_NETHUNTER" ] && [ -d "${'$'}CHROOT" ]; then
                        case "${'$'}CUR_DIR" in
                            "${'$'}CHROOT"*) CHROOT_CWD="${'$'}{CUR_DIR#"${'$'}CHROOT"}"; [ -z "${'$'}CHROOT_CWD" ] && CHROOT_CWD="/" ;;
                            *) CHROOT_CWD="${'$'}CUR_DIR" ;;
                        esac
                        ESC_CWD=${'$'}(printf '%s' "${'$'}CHROOT_CWD" | sed "s/'/'\\\\''/g")
                        run_priv "setsid -w chroot '${'$'}CHROOT' /usr/bin/env -i HOME=/root TERM=xterm-256color PATH=/opt/antigravity/usr/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/bash -c 'cd \"\${'$'}1\" 2>/dev/null || cd /root; eval \"\${'$'}2\"' _ '${'$'}ESC_CWD' '${'$'}ESC_CMD' >'${'$'}LOG' 2>&1 </dev/null & echo \${'$'}! >'${'$'}PIDF'"
                    else
                        run_priv "cd '${'$'}CUR_DIR' 2>/dev/null || cd '$wsDefault'; setsid -w /system/bin/sh -c '${'$'}ESC_CMD' >'${'$'}LOG' 2>&1 </dev/null & echo \${'$'}! >'${'$'}PIDF'"
                    fi

                    sleep 1
                    WIFI_IP=${'$'}(ip -4 addr show 2>/dev/null | awk '/inet / && $2 !~ /^127\./ {sub(/\/.*/, "", $2); print $2; exit}')
                    if [ -f "${'$'}PIDF" ]; then
                        echo "[agy-srv] Started '${'$'}NAME' (PID ${'$'}(cat "${'$'}PIDF")), log: ${'$'}LOG"
                    else
                        echo "[agy-srv] Launched '${'$'}NAME', check log: ${'$'}LOG"
                    fi
                    [ -n "${'$'}WIFI_IP" ] && echo "[agy-srv] URLs: http://127.0.0.1:<port> and http://${'$'}WIFI_IP:<port>"
                    ;;
                stop)
                    NAME="${'$'}1"
                    PIDF="${'$'}SRV_DIR/${'$'}NAME.pid"
                    if [ -f "${'$'}PIDF" ]; then
                        PID="${'$'}(cat "${'$'}PIDF")"
                        run_priv "CHILDREN=\$(ps -ef 2>/dev/null | awk -v p='${'$'}PID' '\$3==p {print \$2}'); for c in \${'$'}CHILDREN; do kill -TERM -\"\${'$'}c\" 2>/dev/null || kill -TERM \"\${'$'}c\" 2>/dev/null || true; done; kill -TERM -'${'$'}PID' 2>/dev/null || kill -9 '${'$'}PID' 2>/dev/null || true"
                        rm -f "${'$'}PIDF"
                        echo "[agy-srv] Stopped '${'$'}NAME' (PID ${'$'}PID)"
                    else
                        echo "[agy-srv] No PID file for '${'$'}NAME'"
                    fi
                    ;;
                list|status)
                    for f in "${'$'}SRV_DIR"/*.pid; do
                        [ -e "${'$'}f" ] || continue
                        NAME="${'$'}(basename "${'$'}f" .pid)"
                        PID="${'$'}(cat "${'$'}f")"
                        if run_priv "kill -0 ${'$'}PID 2>/dev/null"; then
                            echo "RUNNING  ${'$'}NAME  (PID ${'$'}PID)"
                        else
                            echo "DEAD     ${'$'}NAME  (stale PID ${'$'}PID)"
                        fi
                    done
                    ;;
                ports)
                    run_priv "ss -tlnp 2>/dev/null || netstat -tlnp 2>/dev/null || cat /proc/net/tcp /proc/net/tcp6"
                    ;;
                logs)
                    NAME="${'$'}1"
                    tail -n 60 "${'$'}SRV_DIR/${'$'}NAME.log" 2>/dev/null || echo "No log for '${'$'}NAME'"
                    ;;
                open)
                    PORT="${'$'}{1:-8000}"
                    curl -s -X POST "http://127.0.0.1:45161/open_url" -d "{\"url\":\"http://127.0.0.1:${'$'}PORT\"}" >/dev/null 2>&1 || am start -a android.intent.action.VIEW -d "http://127.0.0.1:${'$'}PORT" >/dev/null 2>&1
                    echo "Opened http://127.0.0.1:${'$'}PORT"
                    ;;
                *)
                    echo "agy-srv: start <name> <cmd> | stop <name> | list | ports | logs <name> | open <port>"
                    ;;
            esac
        """.trimIndent() + "\n"

        writeExecutableScript(File(binDir, "kali"), kaliScript)
        writeExecutableScript(File(binDir, "nh"), kaliScript)
        writeExecutableScript(File(binDir, "agy-nh-shell"), nhShellScript)
        writeExecutableScript(File(binDir, "agy-srv"), srvScript)

        val androidBridgeScript = """
            #!/system/bin/sh
            if [ "${'$'}#" -eq 0 ]; then
                exec /system/bin/sh
            else
                exec /system/bin/sh -c "${'$'}*"
            fi
        """.trimIndent() + "\n"
        writeExecutableScript(File(binDir, "android"), androidBridgeScript)
        writeExecutableScript(File(binDir, "android-sh"), androidBridgeScript)

        if (status.chrootPath != null) {
            deployKaliNativeShims(context, chrootPath)
        }

        syncEnvironmentRule(context, status)
    }

    private fun writeExecutableScript(target: File, content: String) {
        try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, ".${target.name}.tmp.${android.os.Process.myTid()}")
            tmp.writeText(content, Charsets.UTF_8)
            tmp.setReadable(true, false)
            tmp.setExecutable(true, false)
            Os.chmod(tmp.absolutePath, 493)
            if (!tmp.renameTo(target)) {
                target.writeText(content, Charsets.UTF_8)
                target.setReadable(true, false)
                target.setExecutable(true, false)
                Os.chmod(target.absolutePath, 493)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing script ${target.absolutePath}: ${e.message}")
        }
    }

    private fun syncEnvironmentRule(context: Context, status: NetHunterEnvStatus) {
        val wsDefault = getDefaultWorkspaceDir().absolutePath
        val chrootText = status.chrootPath ?: "Not detected"
        val wifiIp = getLocalWifiIpAddress(context) ?: "<device-wifi-ip>"
        val ruleText = """
            # Root & Kali NetHunter Android Environment (Dual Host + Linux Context)
            
            - **Current OS & Shell Context**: You are executing natively inside **Kali NetHunter (`$chrootText`, `uid=0` Root: `${status.hasRoot}`)** on a rooted Xiaomi POCO F1 ARM64 device.
            - **Active Execution Mode**: `${status.effectiveMode.displayName}`
            - **Dual Operating System Architecture**:
              1. **Kali Linux Native Tools**: All standard Linux & penetration testing tools (`nmap`, `sqlmap`, `python3`, `bash`, `node`, `npm`, `git`, `curl`, `ip`, `ss`, `tcpdump`, `wireshark`, `tshark`, `metasploit`, `aircrack-ng`) run directly with full root permissions (`uid=0`, `gid=0`).
              2. **Android Host OS Bridge (`android` command)**: You can execute ANY native Android system command directly from within Kali using `android "<command>"` or `android-sh` (powered by `nsenter -t 1 -m /system/bin/sh` into Android's root namespace).
                 - Device properties: `android "getprop ro.product.model"` or `android "getprop ro.build.version.release"`
                 - Package manager: `android "pm list packages -3"`, `android "pm install -r <apk>"`
                 - Activity manager: `android "am start -n <package>/<activity>"`
                 - Hardware & wireless controls: `android "svc wifi enable"`, `android "svc data disable"`, `android "dumpsys battery"`
                 - Android native shell: `android "sh -c '...'"`
              3. **Simultaneous Shared Storage**:
                 - Android shared storage (`/storage/emulated/0` and `/sdcard`) is bind-mounted directly inside your Kali Linux environment at `/sdcard` and `/storage/emulated/0`.
                 - Default shared workspace: `$wsDefault` (identical to `/sdcard/Antigravity`).
                 - All files created in Kali are instantly accessible by Android apps, and vice-versa.
            - **Phone LAN Wi-Fi IP**: `$wifiIp`
            
            ## Critical Execution Rules (Read Carefully!)
            1. **NEVER run `su` or `sudo` inside Kali!** Your shell already executes as `uid=0` root.
            2. **Android Host vs Kali Commands**: For Kali Linux tools (`nmap`, `python3`, `pip`, `git`, `curl`), run them directly. For Android OS-specific commands (`getprop`, `pm`, `am`, `svc`, `settings`, `dumpsys`), prefix with `android "<command>"`.
            3. **Host `/system/bin/sh` Integrity**: The Android host shell is securely pointed to `/system/bin/busybox_nh-1.32`. Never overwrite or create circular symlinks pointing to `/bin/sh`.
            4. **Fast Non-Blocking Network Commands**: When scanning LAN devices on mobile Wi-Fi, ALWAYS skip slow reverse-DNS stalls (`-n`) and cap host timeouts (e.g., `nmap -sn -n -T4 --max-retries 1 --host-timeout 3s 192.168.1.0/24` or `ip -4 neigh show`) with `WaitMsBeforeAsync=10000`.
            5. **Web Servers MUST bind to `0.0.0.0` AND run detached via `agy-srv`** (`/opt/antigravity/usr/bin/agy-srv`):
               - Python: `agy-srv start web "python3 -m http.server 8000 --bind 0.0.0.0"`
               - Node/Vite: `agy-srv start vite "npm run dev -- --host 0.0.0.0 --port 5173"`
               - Next.js: `agy-srv start next "npx next dev -H 0.0.0.0 -p 3000"`
               - Flask: `agy-srv start flask "flask run --host=0.0.0.0 --port=5000"`
               Check status/ports with `agy-srv list`, `agy-srv logs <name>`, `agy-srv ports`.
               Share clickable links `http://127.0.0.1:<port>` (for in-app browser preview) and `http://$wifiIp:<port>` (for LAN Wi-Fi).
            6. **File Links:** Output standard `[filename](file:///exact/path)` links—Antigravity opens `file:///` links directly in the built-in preview/editor.
        """.trimIndent() + "\n"

        listOf(
            File(context.filesDir, ".gemini/config/rules/nethunter-root-env.md"),
            File(context.filesDir, "home/.gemini/config/rules/nethunter-root-env.md")
        ).forEach { ruleFile ->
            try {
                ruleFile.parentFile?.mkdirs()
                ruleFile.writeText(ruleText, Charsets.UTF_8)
                ruleFile.setReadable(true, false)
                ruleFile.setWritable(true, false)
            } catch (_: Exception) {}
        }

        val startMarker = "<!-- AGY_NH_ENV_START -->"
        val endMarker = "<!-- AGY_NH_ENV_END -->"
        val wrappedBlock = "$startMarker\n$ruleText$endMarker\n"
        listOf(
            File(context.filesDir, "home/.gemini/GEMINI.md"),
            File(context.filesDir, "home/.gemini/antigravity-cli/GEMINI.md"),
            File(context.filesDir, ".gemini/config/GEMINI.md")
        ).forEach { geminiFile ->
            try {
                geminiFile.parentFile?.mkdirs()
                val existing = if (geminiFile.exists()) geminiFile.readText(Charsets.UTF_8) else ""
                val updated = if (existing.contains(startMarker) && existing.contains(endMarker)) {
                    existing.replace(Regex("$startMarker[\\s\\S]*?$endMarker\\n?"), wrappedBlock)
                } else if (existing.isBlank()) {
                    wrappedBlock
                } else {
                    "$existing\n\n$wrappedBlock"
                }
                geminiFile.writeText(updated, Charsets.UTF_8)
                geminiFile.setReadable(true, false)
                geminiFile.setWritable(true, false)
            } catch (_: Exception) {}
        }
    }

    fun getLocalWifiIpAddress(context: Context): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
            val candidates = mutableListOf<String>()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (iface.name.startsWith("wlan") || iface.name.startsWith("ap")) {
                            return ip
                        }
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            candidates.add(ip)
                        }
                    }
                }
            }
            candidates.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    fun pathExistsWithRootFallback(file: File): Boolean {
        if (file.exists()) return true
        if (cachedStatus?.hasRoot != true) return false
        val esc = shellEscape(file.absolutePath)
        val (exit, _) = runSuQuick("[ -e '$esc' ]", 2500L)
        return exit == 0
    }

    fun isDirectoryWithRootFallback(file: File): Boolean {
        if (file.exists() && file.isDirectory) return true
        if (cachedStatus?.hasRoot != true) return false
        val esc = shellEscape(file.absolutePath)
        val (exit, _) = runSuQuick("[ -d '$esc' ]", 2500L)
        return exit == 0
    }

    fun isFileWithRootFallback(file: File): Boolean {
        if (file.exists() && file.isFile) return true
        if (cachedStatus?.hasRoot != true) return false
        val esc = shellEscape(file.absolutePath)
        val (exit, _) = runSuQuick("[ -f '$esc' ]", 2500L)
        return exit == 0
    }

    fun createDirectoryWithRootFallback(dir: File): Boolean {
        if (dir.exists() && dir.isDirectory) return true
        if (dir.mkdirs()) return true
        if (cachedStatus?.hasRoot != true) return false
        val esc = shellEscape(dir.absolutePath)
        val (exit, _) = runSuQuick("mkdir -p '$esc' && chmod 0755 '$esc'", 3000L)
        return exit == 0
    }

    fun resolveReadableTarget(context: Context, rawPathOrUri: String, workspacePath: String? = null): File? {
        var clean = rawPathOrUri.trim()
        if (clean.startsWith("file://")) {
            clean = android.net.Uri.parse(clean).path ?: clean.removePrefix("file://")
        }
        clean = clean.substringBefore("#").substringBefore("?").trim()
        if (clean.isBlank()) return null

        val direct = File(clean)
        if (direct.isAbsolute && pathExistsWithRootFallback(direct)) return direct

        if (!workspacePath.isNullOrBlank()) {
            val wsRel = File(workspacePath, clean.trimStart('/'))
            if (pathExistsWithRootFallback(wsRel)) return wsRel
        }

        if (clean.startsWith("/opt/antigravity/")) {
            val mapped = File(context.filesDir, clean.removePrefix("/opt/antigravity/"))
            if (pathExistsWithRootFallback(mapped)) return mapped
        }

        val chroot = cachedStatus?.chrootPath ?: getSavedChrootPath(context) ?: "/data/local/nhsystem/kali-arm64"
        if (clean.startsWith("/") && !clean.startsWith(chroot)) {
            val inChroot = File(chroot, clean.trimStart('/'))
            if (pathExistsWithRootFallback(inChroot)) return inChroot
        }

        return direct
    }

    fun copyFileToAccessibleTemp(context: Context, src: File): File? {
        return try {
            val cacheDir = File(context.cacheDir, "root_preview").apply { mkdirs() }
            val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
            cacheDir.listFiles()?.forEach { old ->
                if (old.isFile && old.lastModified() < cutoff) {
                    try { old.delete() } catch (_: Exception) {}
                }
            }
            val dest = File(cacheDir, "${System.currentTimeMillis()}_${src.name}")
            if (src.exists() && src.canRead()) {
                src.copyTo(dest, overwrite = true)
                dest.setReadable(true, false)
                return dest
            }
            val escSrc = shellEscape(src.absolutePath)
            val escDst = shellEscape(dest.absolutePath)
            val (exit, _) = runSuQuick("cp -f '$escSrc' '$escDst' && chmod 0644 '$escDst'", 6000L)
            if (exit == 0 && dest.exists()) dest else null
        } catch (_: Exception) {
            null
        }
    }

    // Root fallback file operations for FileTree & CodeEditor
    fun listDirectoryWithRootFallback(dir: File): List<RootFileEntry> {
        val nativeList = try { dir.listFiles() } catch (_: Exception) { null }
        if (nativeList != null) {
            return nativeList.map { f ->
                RootFileEntry(
                    name = f.name,
                    absolutePath = f.absolutePath,
                    isDirectory = f.isDirectory,
                    sizeBytes = if (f.isDirectory) 0L else f.length(),
                    lastModified = f.lastModified()
                )
            }
        }
        if (cachedStatus?.hasRoot != true) return emptyList()
        val escDir = shellEscape(dir.absolutePath)
        val cmd = "for p in '$escDir'/* '$escDir'/.*; do " +
            "b=\$(basename \"\$p\"); [ \"\$b\" = '.' ] || [ \"\$b\" = '..' ] && continue; " +
            "[ -e \"\$p\" ] || continue; " +
            "if [ -d \"\$p\" ]; then t=D; sz=0; else t=F; sz=\$(stat -c %s \"\$p\" 2>/dev/null || wc -c < \"\$p\" 2>/dev/null || echo 0); fi; " +
            "mt=\$(stat -c %Y \"\$p\" 2>/dev/null || echo 0); " +
            "printf '%s|%s|%s|%s\\n' \"\$t\" \"\$sz\" \"\$mt\" \"\$b\"; " +
            "done"
        val (exit, out) = runSuQuick(cmd, 5000L)
        if (exit != 0 || out.isBlank()) return emptyList()
        return out.lines().mapNotNull { line ->
            val parts = line.split("|", limit = 4)
            if (parts.size < 4) return@mapNotNull null
            val isDir = parts[0] == "D"
            val size = parts[1].toLongOrNull() ?: 0L
            val mtime = (parts[2].toLongOrNull() ?: 0L) * 1000L
            val name = parts[3]
            if (name.isBlank()) return@mapNotNull null
            RootFileEntry(
                name = name,
                absolutePath = File(dir, name).absolutePath,
                isDirectory = isDir,
                sizeBytes = size,
                lastModified = mtime
            )
        }
    }

    fun readFileTextWithRootFallback(file: File): String? {
        val maxPreviewBytes = 1024 * 1024 // 1 MB safe preview cap
        try {
            if (file.exists() && file.canRead()) {
                val totalLen = file.length()
                val bytes = ByteArray(maxPreviewBytes.coerceAtMost(totalLen.toInt().coerceAtLeast(0)))
                val readCount = file.inputStream().use { input ->
                    var offset = 0
                    while (offset < bytes.size) {
                        val n = input.read(bytes, offset, bytes.size - offset)
                        if (n <= 0) break
                        offset += n
                    }
                    offset
                }
                val sniffLen = readCount.coerceAtMost(4096)
                for (idx in 0 until sniffLen) {
                    if (bytes[idx] == 0.toByte()) {
                        return "[Binary file (${totalLen} bytes) — use 'Open With' or external tools to inspect.]"
                    }
                }
                val text = String(bytes, 0, readCount, Charsets.UTF_8)
                return if (totalLen > maxPreviewBytes) {
                    "$text\n\n... [Truncated preview: showing first 1 MB of $totalLen bytes] ..."
                } else {
                    text
                }
            }
        } catch (_: Exception) {}
        if (cachedStatus?.hasRoot != true) return null
        val esc = shellEscape(file.absolutePath)
        val (exit, out) = runSuQuick("if head -c 4096 '$esc' 2>/dev/null | tr -dc '\\0' | grep -q .; then echo '__AGY_BINARY_FILE__'; else head -c $maxPreviewBytes '$esc'; fi", 5000L)
        if (exit != 0) return null
        return if (out.startsWith("__AGY_BINARY_FILE__")) {
            "[Binary file — use 'Open With' or external tools to inspect.]"
        } else {
            out
        }
    }

    fun writeFileTextWithRootFallback(file: File, content: String): Boolean {
        try {
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            return true
        } catch (_: Exception) {}
        if (cachedStatus?.hasRoot != true) return false
        return try {
            val esc = shellEscape(file.absolutePath)
            val pb = ProcessBuilder("su", "-mm", "-c", "mkdir -p \"\$(dirname '$esc')\" && cat > '$esc'")
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.outputStream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            proc.waitFor(5000L, TimeUnit.MILLISECONDS) && proc.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    fun deleteWithRootFallback(file: File): Boolean {
        try {
            if (file.deleteRecursively()) return true
        } catch (_: Exception) {}
        if (cachedStatus?.hasRoot != true) return false
        val esc = shellEscape(file.absolutePath)
        val (exit, _) = runSuQuick("rm -rf '$esc'", 5000L)
        return exit == 0
    }

    fun copyFileToDownloads(file: File): File? {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: File("/storage/emulated/0/Download")
        if (!downloads.exists()) downloads.mkdirs()
        val dest = File(downloads, file.name)
        try {
            if (file.exists() && file.canRead()) {
                file.copyTo(dest, overwrite = true)
                return dest
            }
        } catch (_: Exception) {}
        if (cachedStatus?.hasRoot != true) return null
        val (exit, _) = runSuQuick("cp -f '${shellEscape(file.absolutePath)}' '${shellEscape(dest.absolutePath)}' && chmod 0666 '${shellEscape(dest.absolutePath)}'", 5000L)
        return if (exit == 0) dest else null
    }

    fun makeGeminiReadable(context: Context) {
        if (cachedStatus?.hasRoot != true) return
        val geminiPath = shellEscape(File(context.filesDir, ".gemini").absolutePath)
        val homeGeminiPath = shellEscape(File(context.filesDir, "home/.gemini").absolutePath)
        runSuQuick("chmod -R a+rwX '$geminiPath' '$homeGeminiPath' 2>/dev/null || true", 3000L)
    }
}
