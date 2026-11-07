package app.ballistic.assistant.core

import kotlin.test.*

class AppAccessTest {
    @Test fun missingAndCorruptPoliciesDenyAccess() {
        for (text in listOf(null, "broken", "{\"version\":999,\"apps\":{}}")) {
            val p = PolicyFile.decodeOrDefault(text).forApp("new.app")
            assertFalse(AppAccess.canOpen(p))
            assertFalse(AppAccess.canReadNotifications(p, true))
            assertEquals(Route.DeviceOnly, p.route)
        }
    }

    @Test fun notificationConsentAndRouteAreIndependentOfActionConsent() {
        val p = AppPolicy(observe = Observe.Notifications)
        assertTrue(AppAccess.canReadNotifications(p, true))
        assertFalse(AppAccess.canReadNotifications(p, false))
        assertFalse(AppAccess.canOpen(p))
        assertTrue(AppAccess.canReadNotifications(p.copy(route = Route.Default), false))
        assertFalse(AppAccess.canReadNotifications(p.copy(observe = Observe.None), true))
    }

    @Test fun dispatchRechecksGrantAndNeverAutomate() {
        var file = PolicyFile()
        var calls = 0
        val hands = PolicyHands({ file }, { calls++; HandsResult.Ok }, { error("not needed") })
        val op = HandsOp.OpenApp("clock")
        assertIs<HandsResult.NotExecuted>(hands.act(op))
        file = file.with("clock", AppPolicy(act = Act.Navigate))
        assertEquals(HandsResult.Ok, hands.act(op))
        file = file.with("clock", AppPolicy(act = Act.Navigate, confirm = ConfirmMode.NeverAutomate))
        assertIs<HandsResult.NotExecuted>(hands.act(op))
        assertEquals(1, calls)
    }

    @Test fun everyActionApprovalCannotSurviveDenialStopOrRevocation() {
        val allow = PolicyFile().with("clock", AppPolicy(act = Act.Navigate, confirm = ConfirmMode.Always))
        for (outcome in listOf("deny", "stop", "revoke", "approve")) {
            var file = allow
            var active = true
            var calls = 0
            var approvals = 0
            val hands = PolicyHands({ file }, { calls++; HandsResult.Ok }, { request ->
                approvals++
                assertTrue(request.hard)
                assertEquals(HandsOp.OpenApp("clock"), request.operation)
                when (outcome) {
                    "stop" -> active = false
                    "revoke" -> file = PolicyFile()
                }
                if (outcome == "deny") ConfirmResult.Denied else ConfirmResult.Approved
            }, { active })
            hands.act(HandsOp.OpenApp("clock"))
            assertEquals(1, approvals)
            assertEquals(if (outcome == "approve") 1 else 0, calls)
        }
    }

    @Test fun unsupportedOperationsNeverReachTheDelegate() {
        val hands = PolicyHands({ PolicyFile() }, { error("must not dispatch") }, { error("must not ask") })
        assertIs<HandsResult.NotExecuted>(hands.act(HandsOp.Tap(1, 2)))
    }
}
