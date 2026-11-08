package app.ballistic.assistant.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TensorDiagnosisTest {
    private val phrase = "not available on this phone"

    // The Pixel 10 Pro log of 8 Nov 2026 17:52 (LiteRT-LM 0.19.1 + Tensor dispatch 2.3.2), in
    // logcat epoch format, with the app path shortened.
    private val abiMismatch = listOf(
        "1762624331.101  9123  9200 W litert  : npu_registry.cc:34 NPU accelerator could not be loaded and registered: kLiteRtStatusErrorNotFound.",
        "1762624331.240  9123  9200 I litert  : litert_dispatch.cc:208 Loading shared library: /data/app/~~Xy==/app.ballistic.assistant-Ab==/lib/arm64/libLiteRtDispatch_GoogleTensor.so",
        "1762624331.241  9123  9200 E litert  : dispatch_delegate.cc:132 Failed to initialize Dispatch API: Could not load symbol LiteRtDispatchQueryInterface: undefined symbol: LiteRtDispatchQueryInterface.",
        "1762624331.241  9123  9200 E litert  : dispatch_delegate.cc:147 Failed to create a dispatch delegate kernel: No usable Dispatch runtime found",
        "1762624331.242  9123  9200 E tflite  : Node number 1 (DELEGATE) failed to prepare.",
    )

    @Test fun packagedDispatchAbiMismatchNamesTheMissingSymbolNotThePhone() {
        val d = TensorDiagnostics.classify(abiMismatch, "LiteRtLmJniException")
        assertEquals(TensorCause.PackagedRuntimeIncompatible, d.cause)
        assertTrue(d.message.startsWith("Ballistic's packaged Tensor runtime is incompatible: LiteRtDispatchQueryInterface"), d.message)
        assertFalse(phrase in d.message)
        val detail = assertNotNull(d.detail)
        assertTrue(detail.startsWith("dispatch_delegate.cc:132 Failed to initialize Dispatch API"), detail)
        assertFalse("/data/app" in detail)
    }

    @Test fun missingDispatchLibraryIsPackaging() {
        val d = TensorDiagnostics.classify(listOf("E litert  : litert_dispatch.cc:160 Dispatch library not found."))
        assertEquals(TensorCause.PackagedRuntimeIncompatible, d.cause)
        val dl = TensorDiagnostics.classify(listOf("E litert  : dlopen failed: library \"/data/app/x/lib/arm64/libLiteRtDispatch_GoogleTensor.so\" not found"))
        assertEquals(TensorCause.PackagedRuntimeIncompatible, dl.cause)
        assertEquals("dlopen failed: library \"libLiteRtDispatch_GoogleTensor.so\" not found", TensorDiagnostics.clean(dl.detail!!).removePrefix("E litert  : "))
    }

    @Test fun legacyAbiAndVersionNegotiationArePackaging() {
        listOf(
            "W litert  : Vendor library 'x.so' exports legacy symbol 'LiteRtDispatchInitialize' instead of 'LiteRtDispatchQueryInterface'.",
            "E litert  : Failed to negotiate basic interface version",
        ).forEach { assertEquals(TensorCause.PackagedRuntimeIncompatible, TensorDiagnostics.classify(listOf(it)).cause, it) }
    }

    @Test fun vendorStackRefusalIsTheVendorNotUs() {
        val selinux = TensorDiagnostics.classify(listOf(
            "I litert  : GoogleTensor Dispatch API version 0.1.0, SB API version 1.2, vendor id: Google",
            "E litert  : [southbound.cc:88] Failed to dlopen 'libedgetpu_litert.so': dlopen failed: library \"libedgetpu_litert.so\" not found",
        ))
        assertEquals(TensorCause.VendorStackRefused, selinux.cause)
        assertTrue("libedgetpu_litert.so" in selinux.message)
        assertFalse(phrase in selinux.message)
        assertEquals(TensorCause.VendorStackRefused, TensorDiagnostics.classify(listOf("E litert  : status PERMISSION_DENIED from edgetpu service")).cause)
        assertEquals(TensorCause.VendorStackRefused, TensorDiagnostics.classify(listOf("E litert  : [x.cc:1] Failed to load SouthBound symbols")).cause)
    }

    @Test fun bundleForAnotherChip() {
        val d = TensorDiagnostics.classify(listOf("E litert  : [sq.cc:40] Failed to load SQ container from FD with offset"))
        assertEquals(TensorCause.BundleNotForThisChip, d.cause)
        assertTrue("not compiled for this phone's Tensor chip" in d.message)
    }

    @Test fun unknownCauseQuotesTheFirstConcreteLineAndNeverBlamesThePhone() {
        val d = TensorDiagnostics.classify(listOf(
            "W litert  : npu_registry.cc:34 NPU accelerator could not be loaded and registered: kLiteRtStatusErrorNotFound.",
            "I litert  : something normal",
            "E litert  : executor.cc:9 Something odd failed: code 13",
        ), "LiteRtLmJniException")
        assertEquals(TensorCause.Unclassified, d.cause)
        assertEquals("E litert  : executor.cc:9 Something odd failed: code 13".removePrefix("E litert  : "), d.detail!!.removePrefix("E litert  : "))
        assertTrue("LiteRtLmJniException" in d.message)
        assertFalse(phrase in d.message)
        val empty = TensorDiagnostics.classify(emptyList())
        assertEquals(TensorCause.Unclassified, empty.cause)
        assertNull(empty.detail)
    }

    @Test fun precheckSeparatesDeviceFromPackaging() {
        assertEquals(TensorCause.NotTensorDevice, TensorDiagnostics.precheck("x86_64", "Google", "", true)!!.cause)
        assertEquals(TensorCause.NotTensorDevice, TensorDiagnostics.precheck("arm64-v8a", "Qualcomm", "SM8750", true)!!.cause)
        val absent = TensorDiagnostics.precheck("arm64-v8a", "Google", "Tensor G5", false)!!
        assertEquals(TensorCause.PackagedRuntimeIncompatible, absent.cause)
        assertTrue(absent.message.startsWith("Ballistic's packaged Tensor runtime is incompatible"))
        assertNull(TensorDiagnostics.precheck("arm64-v8a", "Google", "Tensor G5", true))
        listOf(TensorDiagnostics.precheck("x86_64", "Google", "", true)!!, absent).forEach { assertFalse(phrase in it.message) }
    }

    @Test fun cleanStripsLogcatPrefixAndPaths() {
        assertEquals("litert_dispatch.cc:208 Loading shared library: libLiteRtDispatch_GoogleTensor.so",
            TensorDiagnostics.clean(abiMismatch[1]))
        assertTrue(TensorDiagnostics.clean("x".repeat(500)).length <= 240)
    }

    @Test fun summaryCountsDelegatedNodes() {
        val lines = listOf(
            "I tflite  : Replacing 12 out of 12 node(s) with delegate (DispatchDelegate) node, yielding 1 partitions for subgraph 0.",
            "I tflite  : Replacing 1306 out of 1306 node(s) with delegate (WEBGPU) node",
            "I litert  : GoogleTensor Dispatch API version 0.1.0, SB API version 1.2, vendor id: Google",
        )
        assertEquals(listOf(TensorDiagnostics.Delegation(12, 12, "DispatchDelegate"), TensorDiagnostics.Delegation(1306, 1306, "WEBGPU")),
            TensorDiagnostics.delegations(lines))
        val s = TensorDiagnostics.summary(lines)
        assertTrue("12/12 nodes on DispatchDelegate" in s && "Google Tensor dispatch runtime initialized" in s, s)
        assertEquals("LiteRT's log for this start has no accelerator or delegation lines.", TensorDiagnostics.summary(emptyList()))
    }
}
