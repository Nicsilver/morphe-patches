package app.morphe.patches.youtube.layout.shortsplayer

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.navigation.hookNavigationButtonCreated
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.playertype.ReelWatchPagerFingerprint
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.misc.toolbar.hookToolBar
import app.morphe.patches.youtube.misc.toolbar.toolBarHookPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/DimShortsOverlayPatch;"

@Suppress("unused")
val dimShortsOverlayPatch = bytecodePatch(
    name = "Dim Shorts overlay",
    description = "Adds an option to reduce the brightness of the Shorts overlay to prevent OLED burn-in.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playerTypeHookPatch,
        toolBarHookPatch,
        navigationBarHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.SHORTS.addPreferences(
            TextPreference("morphe_shorts_overlay_opacity", inputType = InputType.NUMBER),
            SwitchPreference("morphe_shorts_immersive_mode"),
        )

        hookToolBar("$EXTENSION_CLASS->dimShortsToolbarButton")

        hookNavigationButtonCreated(EXTENSION_CLASS)

        ReelWatchPagerFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "invoke-static { v$register }, " +
                            "$EXTENSION_CLASS->dimShortsPlayerOverlay(Landroid/view/View;)V",
                )
            }
        }
    }
}
