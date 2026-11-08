// The short model picker on Local AI (owner-approved defaults, 9 Nov 2026): two recommended
// models for this phone, best and small, in plain words with their size; everything else under
// "More models". On a Tensor G5 phone the G5 bundle is the best pick; bundles compiled for the
// Tensor NPU are hidden on phones without a Google Tensor chip; gated entries are only under
// More models. The recommendation is the measured one (LOCAL_AI.md, "What was measured where"):
// on the Pixel 10 Pro the G5 bundle and the 0.6B starter are the two entries measured on a phone.

package app.ballistic.assistant.models

import java.util.Locale

/** What the app can read about the phone's chip. Not a capability promise. */
data class PhoneChip(val arm64: Boolean, val googleTensor: Boolean, val tensorG5: Boolean) {
    companion object {
        fun of(abi: String, socManufacturer: String, socModel: String): PhoneChip {
            val arm = abi == "arm64-v8a"
            val tensor = arm && socManufacturer.equals("Google", ignoreCase = true)
            // Pixel 10 phones report SOC_MODEL "Tensor G5"; earlier Tensor chips report G1-G4.
            val g5 = tensor && socModel.replace(" ", "").contains("TensorG5", ignoreCase = true)
            return PhoneChip(arm, tensor, g5)
        }
    }
}

data class ModelPick(val entry: DownloadableModel, val why: String)
data class PickerLists(val recommended: List<ModelPick>, val more: List<DownloadableModel>)

object ModelPicker {
    const val TENSOR_G5_BEST = "gemma4-e2b-tensor-g5"
    const val SMALL = "qwen3-06b-q8-preview"
    /** Best pick without a Tensor G5: the larger model on the same CPU engine as the starter. */
    const val OTHER_BEST = "qwen3-17b-q8"

    /** Whether the entry can be offered at all on this phone. */
    fun offered(entry: DownloadableModel, chip: PhoneChip): Boolean =
        entry.accelerator != Accelerator.TensorG5Npu || chip.googleTensor

    fun lists(catalogue: List<DownloadableModel>, chip: PhoneChip): PickerLists {
        val shown = catalogue.filter { offered(it, chip) }
        val byId = shown.associateBy { it.id }
        val best = if (chip.tensorG5) byId[TENSOR_G5_BEST] else byId[OTHER_BEST]
        val picks = listOfNotNull(
            best?.takeUnless { it.gated }?.let { ModelPick(it, "Best on this phone") },
            byId[SMALL]?.takeUnless { it.gated }?.let { ModelPick(it, "Small and quick") },
        ).distinctBy { it.entry.id }
        val chosen = picks.map { it.entry.id }.toSet()
        return PickerLists(picks, shown.filter { it.id !in chosen })
    }

    /** Where the model runs, in plain words. */
    fun whereItRuns(entry: DownloadableModel?): String = when (entry?.accelerator) {
        Accelerator.TensorG5Npu -> "Runs on the Tensor chip"
        Accelerator.Gpu -> "Runs on the graphics chip"
        else -> "Runs on the CPU"
    }

    /** "3.2 GB" (decimal, as download sizes are usually given). */
    fun size(bytes: Long): String = if (bytes >= 1_000_000_000) String.format(Locale.ROOT, "%.1f GB", bytes / 1e9)
        else String.format(Locale.ROOT, "%d MB", Math.round(bytes / 1e6))

    /** One line for the download dialog. */
    fun downloadLine(entry: DownloadableModel): String = "${size(entry.bytes)} download, checked after download"
}
