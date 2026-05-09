package app.morphe.extension.music.patches;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.widget.TextView;

import java.util.WeakHashMap;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public class CopySongInfoPatch {

    private static final WeakHashMap<View, Boolean> listenedViews = new WeakHashMap<>();

    /**
     * Injection point.
     */
    public static void onMusicActivityCreate(Activity activity) {
        View rootView = activity.getWindow().getDecorView();
        int titleId = activity.getResources().getIdentifier("title", "id", activity.getPackageName());
        int subtitleId = activity.getResources().getIdentifier("subtitle", "id", activity.getPackageName());

        if (titleId == 0 && subtitleId == 0) {
            Logger.printDebug(() -> "CopySongInfo: title/subtitle resource IDs not found");
            return;
        }

        rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (!Settings.COPY_SONG_INFO.get()) return;
            if (titleId != 0) attachLongClickCopy(rootView.findViewById(titleId));
            if (subtitleId != 0) attachLongClickCopy(rootView.findViewById(subtitleId));
        });
    }

    private static void attachLongClickCopy(View view) {
        if (!(view instanceof TextView tv)) return;
        if (listenedViews.containsKey(view)) return;
        listenedViews.put(view, Boolean.TRUE);

        view.setOnLongClickListener(v -> {
            CharSequence text = tv.getText();
            if (text == null || text.length() == 0) return false;
            Utils.setClipboard(text.toString());
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                Utils.showToastShort("Copied: " + text);
            }
            return true;
        });
    }
}
