package app.morphe.patches.music.interaction.copysonginfo

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.shared.MusicActivityOnCreateFingerprint
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/CopySongInfoPatch;"

@Suppress("unused")
val copySongInfoPatch = bytecodePatch(
    name = "Copy song info",
    description = "Adds an option to copy the song title or artist by long-pressing them in the player.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_copy_song_info"),
        )

        MusicActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS->onMusicActivityCreate(Landroid/app/Activity;)V",
        )
    }
}
