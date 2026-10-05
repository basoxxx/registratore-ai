package it.registratoreai.desktop

/**
 * Impedisce al computer di andare in standby mentre l'app registra o elabora
 * (lo schermo può comunque spegnersi). Usa gli strumenti di sistema:
 * - macOS: `caffeinate -i` (legato al processo dell'app, si chiude da solo se l'app termina);
 * - Windows: SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED) da PowerShell;
 * - Linux: `systemd-inhibit`.
 */
object SleepGuard {
    private var process: Process? = null
    private val holders = mutableSetOf<String>()

    @Synchronized
    fun acquire(reason: String) {
        holders += reason
        if (process?.isAlive == true) return
        process = runCatching { ProcessBuilder(command()).redirectErrorStream(true).start() }.getOrNull()
    }

    @Synchronized
    fun release(reason: String) {
        holders -= reason
        if (holders.isEmpty()) {
            process?.destroy()
            process = null
        }
    }

    val active: Boolean @Synchronized get() = process?.isAlive == true

    private fun command(): List<String> {
        val pid = ProcessHandle.current().pid()
        return when {
            Paths.isMac -> listOf("caffeinate", "-i", "-w", pid.toString())
            Paths.isWindows -> listOf(
                "powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
                "\$s='[DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint f);';" +
                    "\$t=Add-Type -MemberDefinition \$s -Name P -Namespace RL -PassThru;" +
                    "while (Get-Process -Id $pid -ErrorAction SilentlyContinue) {" +
                    " \$t::SetThreadExecutionState([uint32]'0x80000001') | Out-Null; Start-Sleep -Seconds 20 }",
            )
            else -> listOf(
                "systemd-inhibit", "--what=idle:sleep", "--who=Registratore Lezioni",
                "--why=Registrazione o trascrizione in corso", "sh", "-c",
                "while kill -0 $pid 2>/dev/null; do sleep 20; done",
            )
        }
    }
}
