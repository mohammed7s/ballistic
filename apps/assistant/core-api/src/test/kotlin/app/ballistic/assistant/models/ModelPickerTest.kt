package app.ballistic.assistant.models

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelPickerTest {
    private val catalogue = parseModelCatalogue(File(System.getProperty("ballistic.repo") ?: "../..")
        .resolve("apps/assistant/app/src/main/assets/model_catalogue.json").readText())
    private val g5 = PhoneChip.of("arm64-v8a", "Google", "Tensor G5")
    private val g4 = PhoneChip.of("arm64-v8a", "Google", "Tensor G4")
    private val other = PhoneChip.of("arm64-v8a", "Qualcomm", "SM8650")
    private val emulator = PhoneChip.of("x86_64", "Google", "")

    @Test
    fun chipDetection() {
        assertTrue(g5.tensorG5 && g5.googleTensor)
        assertTrue(g4.googleTensor && !g4.tensorG5)
        assertFalse(other.googleTensor)
        assertFalse(emulator.googleTensor)
    }

    @Test
    fun tensorG5PhoneGetsG5BundleFirstThenTheSmallStarter() {
        val lists = ModelPicker.lists(catalogue, g5)
        assertEquals(listOf(ModelPicker.TENSOR_G5_BEST, ModelPicker.SMALL), lists.recommended.map { it.entry.id })
        assertEquals(catalogue.size, lists.recommended.size + lists.more.size)
    }

    @Test
    fun npuBundlesHiddenWithoutTensor() {
        for (chip in listOf(other, emulator)) {
            val lists = ModelPicker.lists(catalogue, chip)
            val all = lists.recommended.map { it.entry } + lists.more
            assertTrue(all.none { it.accelerator == Accelerator.TensorG5Npu })
            assertEquals(listOf(ModelPicker.OTHER_BEST, ModelPicker.SMALL), lists.recommended.map { it.entry.id })
        }
    }

    @Test
    fun gatedEntriesOnlyUnderMore() {
        for (chip in listOf(g5, g4, other)) {
            val lists = ModelPicker.lists(catalogue, chip)
            assertTrue(lists.recommended.none { it.entry.gated })
        }
        assertTrue(ModelPicker.lists(catalogue, g5).more.any { it.gated })
    }

    @Test
    fun plainWords() {
        val g5Entry = catalogue.first { it.id == ModelPicker.TENSOR_G5_BEST }
        assertEquals("3.2 GB download, checked after download", ModelPicker.downloadLine(g5Entry))
        assertEquals("639 MB", ModelPicker.size(639_446_688))
        assertEquals("Runs on the Tensor chip", ModelPicker.whereItRuns(g5Entry))
        assertEquals("Runs on the CPU", ModelPicker.whereItRuns(null))
    }

    @Test
    fun provenanceFields() {
        val qwen = catalogue.first { it.id == ModelPicker.SMALL }
        assertEquals("Qwen", qwen.publisher)
        assertEquals("Qwen/Qwen3-0.6B-GGUF", qwen.repository)
        assertEquals("23749fefcc72300e3a2ad315e1317431b06b590a", qwen.revision)
        catalogue.forEach { assertEquals(40, it.revision.length) }
    }

    @Test
    fun speedWordsFromTheRealisticCheck() {
        val metrics = LocalMetrics(2441, 16, 800.0, 714.0, firstTokenMs = 900.0, durationMs = 1700.0, reportedGenerationTokensPerSecond = 21.0)
        val result = SpeedResult("m", "M", ModelRuntime.LiteRtLm, ComputeBackend.NPU, null, SpeedCheck.LongPrefill, metrics)
        val record = SpeedWords.record(result, now = 1)!!
        assertEquals("About 4 s per step", SpeedWords.stepLine(record))
        assertEquals("first word 0.9 s · 16 words/s", SpeedWords.testLine(record))
        assertNull(SpeedWords.record(result.copy(check = SpeedCheck.Short), now = 1))
        assertNull(SpeedWords.stepLine(null))
    }

    @Test
    fun speedRecordsPersistNewestPerModelAndBackend() {
        val dir = Files.createTempDirectory("speed").toFile()
        try {
            val store = SpeedRecordStore(dir.resolve("speed.json"))
            assertNull(store.latest("m", null))
            store.save(SpeedRecord("m", ComputeBackend.CPU, 2000, 9000.0, 5.0, 1))
            store.save(SpeedRecord("m", ComputeBackend.CPU, 2000, 8000.0, 6.0, 2))
            store.save(SpeedRecord("m", ComputeBackend.GPU, 2000, 3000.0, 9.0, 3))
            assertEquals(8000.0, store.latest("m", ComputeBackend.CPU)!!.firstTokenMs)
            assertEquals(2, store.load().size)
            assertEquals(ComputeBackend.GPU, store.latest("m", null)!!.backend)
            dir.resolve("speed.json").writeText("broken")
            assertTrue(store.load().isEmpty())
        } finally { dir.deleteRecursively() }
    }
}
