// Why a Tensor NPU start did not happen, told apart from LiteRT's own log lines.
//
// Can: name one of four causes (not an ARM64 Google Tensor device; Ballistic's packaged dispatch
// library missing or built for another Dispatch ABI; the phone's vendor NPU stack refusing or
// hiding itself; a bundle not compiled for this chip or runtime), quote the first concrete LiteRT
// error line with paths reduced to file names, and summarise accelerator registration and graph
// delegation counts.
// Cannot: see a native crash, or prove where a graph ran beyond what LiteRT logged. A cause the
// log does not name is reported as unclassified with its line, never as "not available on this
// phone". Pure Kotlin so the rules are unit-tested on the JVM.

package app.ballistic.assistant.models

enum class TensorCause {
    /** (a) Not ARM64, or the SoC is not Google Tensor. */
    NotTensorDevice,
    /** (b) Our APK: dispatch library absent, or exports a different Dispatch ABI than the runtime needs. */
    PackagedRuntimeIncompatible,
    /** (c) The phone's vendor NPU stack refused the app (permission, SELinux) or is not visible to it. */
    VendorStackRefused,
    /** (d) The bundle was not compiled for this chip or this runtime. */
    BundleNotForThisChip,
    /** LiteRT failed, but its log names none of the causes above. */
    Unclassified,
}

data class TensorDiagnosis(val cause: TensorCause, val message: String, val detail: String? = null)

object TensorDiagnostics {
    const val DISPATCH_LIBRARY = "libLiteRtDispatch_GoogleTensor.so"
    private const val NOTHING_ELSE = "Nothing was switched to another backend or a connected model."

    /** (a) and the packaged-library half of (b), from facts the app can read before any start. */
    fun precheck(abi: String, socManufacturer: String, socModel: String, dispatchPackaged: Boolean): TensorDiagnosis? = when {
        abi != "arm64-v8a" -> TensorDiagnosis(TensorCause.NotTensorDevice,
            "This device is $abi, not ARM64 (for example an emulator); Tensor NPU bundles need a Google Tensor phone.")
        !socManufacturer.equals("Google", ignoreCase = true) -> TensorDiagnosis(TensorCause.NotTensorDevice,
            "This phone's SoC reports \"${"$socManufacturer $socModel".trim()}\", not Google Tensor; Tensor NPU bundles need a Google Tensor phone.")
        !dispatchPackaged -> TensorDiagnosis(TensorCause.PackagedRuntimeIncompatible,
            "Ballistic's packaged Tensor runtime is incompatible: $DISPATCH_LIBRARY is not installed with this app (a packaging fault in this build, not the phone).")
        else -> null
    }

    private val undefinedSymbol = Regex("""(?:undefined symbol|Could not load symbol)[: ]+\s*(LiteRt\w+)""")
    private val missingDispatch = listOf("Dispatch library not found", "Dispatch library directory option not set")
    private val dispatchAbi = listOf("Failed to negotiate basic interface version", "exports legacy symbol",
        "Dispatch initialize entry point not found", "kLiteRtStatusErrorWrongVersion")
    private val vendorLibrary = Regex("""(libedgetpu\w*\.so|libdarwinn\w*\.so|edgetpu_app_service\S*\.so)""")
    private val vendorRefusal = listOf("avc: denied", "PERMISSION_DENIED", "Permission denied", "EACCES",
        "Failed to load SouthBound symbols", "Failed to initialize SB", "Failed to create SB context")
    private val bundleMismatch = listOf("Failed to load SQ container", "Failed to mmap SQ bytecode", "Invalid executable type",
        "UnsupportedCompilerVersion", "UnsupportedRuntimeVersion", "UnsupportedSocModel", "compiled for", "SoC model mismatch",
        "not compatible with this device", "incompatible SoC")
    private val failureWords = Regex("""(?i)fail|error|not found|undefined|denied|could not|unable|invalid|unsupported|mismatch""")
    // Every LiteRT start logs this (the optional NPU accelerator plugin is not part of LiteRT-LM's
    // dispatch path), so it is never the concrete cause by itself.
    private const val REGISTRY_NOISE = "NPU accelerator could not be loaded"

    /** Classifies a failed NPU start from LiteRT's log lines for that start (oldest first). */
    fun classify(lines: List<String>, exceptionName: String? = null): TensorDiagnosis {
        undefinedSymbol.firstMatch(lines)?.let { (line, m) ->
            return TensorDiagnosis(TensorCause.PackagedRuntimeIncompatible,
                "Ballistic's packaged Tensor runtime is incompatible: ${m.groupValues[1]} is missing from $DISPATCH_LIBRARY " +
                    "(the dispatch library was built for a different LiteRT Dispatch ABI than this app's LiteRT-LM). " +
                    "This is a packaging fault in this build, not the phone. $NOTHING_ELSE", clean(line))
        }
        lines.firstOrNull { l -> missingDispatch.any { it in l } || (DISPATCH_LIBRARY in l && ("dlopen failed" in l || "not found" in l)) }?.let {
            return TensorDiagnosis(TensorCause.PackagedRuntimeIncompatible,
                "Ballistic's packaged Tensor runtime is incompatible: LiteRT could not find or open $DISPATCH_LIBRARY in the app's library directory. $NOTHING_ELSE", clean(it))
        }
        lines.firstOrNull { l -> dispatchAbi.any { it in l } }?.let {
            return TensorDiagnosis(TensorCause.PackagedRuntimeIncompatible,
                "Ballistic's packaged Tensor runtime is incompatible: $DISPATCH_LIBRARY speaks a different LiteRT Dispatch ABI version. $NOTHING_ELSE", clean(it))
        }
        lines.firstOrNull { l -> vendorRefusal.any { it in l } || (vendorLibrary.containsMatchIn(l) && failureWords.containsMatchIn(l)) }?.let {
            val lib = vendorLibrary.find(it)?.value
            return TensorDiagnosis(TensorCause.VendorStackRefused,
                "This phone's Tensor NPU stack refused Ballistic or is not visible to it" + (lib?.let { l -> " ($l)" } ?: "") +
                    ": permission, SELinux or the OS's vendor library list. Ballistic's own runtime loaded. $NOTHING_ELSE", clean(it))
        }
        lines.firstOrNull { l -> bundleMismatch.any { it in l } }?.let {
            return TensorDiagnosis(TensorCause.BundleNotForThisChip,
                "This model bundle was not compiled for this phone's Tensor chip or this runtime version. $NOTHING_ELSE", clean(it))
        }
        val line = firstConcreteError(lines)
        return TensorDiagnosis(TensorCause.Unclassified,
            "LiteRT-LM could not start its NPU backend with this bundle" + (exceptionName?.let { " ($it)" } ?: "") +
                "; LiteRT's log does not name a known cause. $NOTHING_ELSE", line)
    }

    /** First line that reports a failure, other than the accelerator-registry line every start logs. */
    fun firstConcreteError(lines: List<String>): String? =
        (lines.firstOrNull { failureWords.containsMatchIn(it) && REGISTRY_NOISE !in it }
            ?: lines.firstOrNull { failureWords.containsMatchIn(it) })?.let(::clean)

    /**
     * Code-owned summary of a start: accelerator registration and "N/M nodes on <delegate>" counts,
     * which is LiteRT's own report of where the graph was placed.
     */
    fun summary(lines: List<String>): String {
        val parts = mutableListOf<String>()
        if (lines.any { "npu_registry" in it && "could not be loaded" in it }) parts += "NPU accelerator plugin not loaded"
        if (lines.any { "RegisterAccelerator" in it && Regex("NPU|Npu|Tensor|Dispatch").containsMatchIn(it) }) parts += "NPU accelerator registered"
        if (lines.any { "GoogleTensor Dispatch API version" in it }) parts += "Google Tensor dispatch runtime initialized"
        delegations(lines).take(4).forEach { parts += "${it.delegated}/${it.total} nodes on ${it.delegate}" }
        return if (parts.isEmpty()) "LiteRT's log for this start has no accelerator or delegation lines."
        else "LiteRT's log for this start: " + parts.joinToString("; ") + "."
    }

    data class Delegation(val delegated: Int, val total: Int, val delegate: String)
    private val delegated = Regex("""Replacing (\d+) out of (\d+) node\(s\) with delegate \(([A-Za-z0-9_]{1,40})\)""")
    fun delegations(lines: List<String>): List<Delegation> = lines.mapNotNull { delegated.find(it) }
        .map { Delegation(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3]) }.distinct()

    private fun Regex.firstMatch(lines: List<String>) = lines.firstNotNullOfOrNull { l -> find(l)?.let { l to it } }

    private val logcatPrefix = Regex("""^\s*[\d.]+\s+\d+\s+\d+\s+[VDIWEF]\s+[\w./-]+\s*:\s*""")
    private val path = Regex("""/[\w.+=~@-]+(?:/[\w.+=~@-]+)+""")
    /** Drops the logcat prefix, reduces every absolute path to its file name and caps the length. */
    fun clean(line: String): String =
        line.replace(logcatPrefix, "").replace(path) { it.value.substringAfterLast('/') }.trim().take(240)
}
