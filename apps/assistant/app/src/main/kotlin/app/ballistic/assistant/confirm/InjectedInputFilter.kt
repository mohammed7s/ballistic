// The confirmation window's input filter (SPEC.md §7.1; docs/FACTS.md; docs/CONTEXT.md §6).
// Every touch reaching the window passes through check(); a rejected event never reaches the
// Approve button. Denying needs no filter (a forged Deny only makes the assistant stop), so the
// filter protects the approve path only.
//
// What each check rests on:
//  1. InputManager.verifyInputEvent(): the event was dispatched by the system InputDispatcher
//     and its verified fields (device id, source, display id, action, the obscured flags) were
//     not altered in our process. A MotionEvent built or edited inside the app fails this. It
//     does NOT reveal POLICY_FLAG_INJECTED: an event injected with INJECT_EVENTS (adb `input`,
//     instrumentation) is still dispatched by the system, so checks 2–5 stand in for that flag.
//  2. Device id KeyCharacterMap.VIRTUAL_KEYBOARD (-1): the id the framework gives injected and
//     synthetic events that have no real device.
//  3. The device behind the id: must exist, be non-virtual, internal (not USB/Bluetooth), a
//     touchscreen, and not one of Computer Control's virtual input devices (vendor 0x0000,
//     product 0xCC01/0xCC03; verified in AOSP android17-release, docs/FACTS.md). The
//     event must be on the display the window is on (Computer Control uses its own display).
//  4. MotionEvent flag FLAG_IS_ACCESSIBILITY_EVENT (0x800, set by InputDispatcher for
//     accessibility-injected input; verified in AOSP), and the obscured / partially obscured
//     flags (another window drawing over ours).
//  5. Tool type finger (not mouse or stylus emulation).
//
// Moves: verifyInputEvent() fails on ACTION_MOVE (batched samples, predicted sample), so moves
// inside a gesture whose DOWN passed get the lighter checkMove(); DOWN and UP are fully checked.
//
// Untested: none of this has run on a device. In particular not yet measured: whether adb
// `input tap` events on Android 17 carry device id -1 or a real touchscreen id (if the latter,
// checks 2–3 pass them and only the biometric protects hard approvals); what an
// AccessibilityService ACTION_CLICK does (it is not an input event at all; see ConfirmActivity's
// recent-verified-touch rule); and the 1,000-injected-tap test in SPEC.md §9. Until then this
// filter is a defence for soft confirmations and a second layer for hard ones, whose real
// boundary is the biometric-bound key (OperationKey).

package app.ballistic.assistant.confirm

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.VerifiedMotionEvent

class InjectedInputFilter(context: Context) {
    private val inputManager = context.getSystemService(InputManager::class.java)

    data class Verdict(val accepted: Boolean, val reason: String)

    fun check(event: MotionEvent, windowDisplayId: Int): Verdict {
        val verified = inputManager.verifyInputEvent(event) as? VerifiedMotionEvent
            ?: return reject("not verified by the input system")
        if (verified.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) return reject("virtual device id")
        if (verified.displayId != windowDisplayId) return reject("event from another display")
        if (verified.source and InputDevice.SOURCE_TOUCHSCREEN != InputDevice.SOURCE_TOUCHSCREEN) return reject("not a touchscreen source")
        if (verified.getFlag(MotionEvent.FLAG_WINDOW_IS_OBSCURED) == true ||
            verified.getFlag(MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) == true
        ) return reject("window obscured")
        if (event.flags and FLAG_IS_ACCESSIBILITY_EVENT != 0) return reject("accessibility event")

        val device = InputDevice.getDevice(verified.deviceId) ?: return reject("unknown device")
        if (device.isVirtual) return reject("virtual device")
        if (device.isExternal) return reject("external device")
        if (!device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) return reject("device is not a touchscreen")
        if (device.vendorId == 0 && device.productId in COMPUTER_CONTROL_PRODUCT_IDS) return reject("Computer Control input device")

        for (i in 0 until event.pointerCount) {
            if (event.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) return reject("not a finger")
        }
        return Verdict(true, "internal touchscreen")
    }

    /**
     * Unverified moves inside a gesture whose DOWN passed [check] (verifyInputEvent fails on
     * batched/predicted move samples). Same device as the DOWN, touchscreen, finger, no
     * accessibility flag. A move cannot approve; only the fully checked UP can.
     */
    fun checkMove(event: MotionEvent, downDeviceId: Int): Verdict {
        if (event.deviceId != downDeviceId) return reject("move from another device")
        if (event.source and InputDevice.SOURCE_TOUCHSCREEN != InputDevice.SOURCE_TOUCHSCREEN) return reject("not a touchscreen source")
        if (event.flags and FLAG_IS_ACCESSIBILITY_EVENT != 0) return reject("accessibility event")
        for (i in 0 until event.pointerCount) {
            if (event.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) return reject("not a finger")
        }
        return Verdict(true, "move within a verified gesture")
    }

    private fun reject(reason: String) = Verdict(false, reason)

    companion object {
        /** MotionEvent.FLAG_IS_ACCESSIBILITY_EVENT; the constant is hidden from the SDK. */
        const val FLAG_IS_ACCESSIBILITY_EVENT = 0x800

        /** Computer Control's virtual touchscreen and dpad (docs/FACTS.md). */
        val COMPUTER_CONTROL_PRODUCT_IDS = setOf(0xCC01, 0xCC03)
    }
}
