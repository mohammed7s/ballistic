// Whether Ballistic holds the assistant role (RoleManager.ROLE_ASSISTANT, "Digital assistant
// app"). Computer Control session creation requires it when the platform flag
// computer_control_role_assistant_requirement is on (AOSP ComputerControlAllowlistController,
// isPackageAllowedToCreateSession), which it is in android17-release.
//
// What qualifies the app (AOSP packages/modules/Permission, role-controller
// AssistantRoleBehavior.getQualifyingPackagesInternal, android17-release): either an exported
// activity resolving ACTION_ASSIST with MATCH_DEFAULT_ONLY (so it needs CATEGORY_DEFAULT), or a
// VoiceInteractionService with BIND_VOICE_INTERACTION and session/recognition services. The
// manifest uses the first: MainActivity handles android.intent.action.ASSIST. The role is
// declared requestable="false", so the app cannot ask for it with createRequestRoleIntent; the
// owner picks Ballistic in Settings > Apps > Default apps > Digital assistant app (Settings.ACTION_VOICE_INPUT_SETTINGS).
// https://developer.android.com/reference/android/app/role/RoleManager#ROLE_ASSISTANT

package app.ballistic.assistant.adapters

import android.app.role.RoleManager
import android.content.Context

object AssistantRole {
    /** True when this device has the role and Ballistic holds it for this user. */
    fun held(context: Context): Boolean {
        val roles = context.getSystemService(RoleManager::class.java) ?: return false
        return roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }
}
