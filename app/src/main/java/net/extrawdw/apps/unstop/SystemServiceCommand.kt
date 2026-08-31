package net.extrawdw.apps.unstop

/** A command handled by a named Android Binder system service. */
internal data class SystemServiceCommand(
    val serviceName: String,
    val arguments: List<String>,
) {
    init {
        require(serviceName.isNotBlank()) { "System service name is blank" }
        require(arguments.none { it.contains('\u0000') }) { "Command argument contains NUL" }
    }

    val diagnosticName: String
        get() = "cmd $serviceName ${arguments.joinToString(" ")}".trim()
}

internal object SystemServiceCommands {
    fun packageManager(vararg arguments: String) = command(PACKAGE_SERVICE, *arguments)

    fun activity(vararg arguments: String) = command(ACTIVITY_SERVICE, *arguments)

    private fun command(serviceName: String, vararg arguments: String) =
        SystemServiceCommand(serviceName, arguments.toList())

    private const val PACKAGE_SERVICE = "package"
    private const val ACTIVITY_SERVICE = "activity"
}
