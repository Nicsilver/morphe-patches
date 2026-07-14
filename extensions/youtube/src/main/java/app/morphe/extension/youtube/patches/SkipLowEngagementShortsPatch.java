/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

/**
 * Skips low-engagement Shorts (likes below {@link Settings#SHORTS_MIN_LIKES} or comments below
 * {@link Settings#SHORTS_MIN_COMMENTS}) by seeking to the end of the Short. When the user has
 * autoplay / auto-advance enabled, seeking to the end causes the reel to advance to the next
 * Short, giving an effective "skip".
 *
 * <p>Like and comment counts are captured from the Shorts action bar accessibility labels by
 * {@link app.morphe.extension.youtube.patches.components.ShortsFilter}. The parsing is
 * best-effort and locale dependent; enable debug logging to see the raw labels and tune.
 *
 * <p>NOTE: this is experimental and needs on-device tuning. If a count cannot be parsed it is
 * treated as unknown and does not by itself trigger a skip.
 */
@SuppressWarnings("unused")
public final class SkipLowEngagementShortsPatch {

    private static final long UNKNOWN = -1L;

    /**
     * Matches a leading number with optional decimal part and an optional magnitude suffix,
     * e.g. "1,234", "1.2K", "1.2M", "12 t.", "3,4 mio.".
     */
    private static final Pattern COUNT_PATTERN =
            Pattern.compile("(\\d[\\d.,\\u00a0 ]*)\\s*([kKmMbBtT])?");

    private static volatile String currentVideoId = "";
    private static volatile long likeCount = UNKNOWN;
    private static volatile long commentCount = UNKNOWN;

    /**
     * Video id that has already been skipped, so we only seek once per Short.
     */
    private static volatile String skippedVideoId = "";

    private static boolean featureEnabled() {
        return Settings.SKIP_LOW_ENGAGEMENT_SHORTS.get();
    }

    /**
     * Resets captured counts when a different Short becomes active.
     */
    private static void syncCurrentVideo() {
        String videoId = VideoInformation.getVideoId();
        if (videoId != null && !videoId.equals(currentVideoId)) {
            currentVideoId = videoId;
            likeCount = UNKNOWN;
            commentCount = UNKNOWN;
        }
    }

    /**
     * Called by ShortsFilter with the like button accessibility label.
     */
    public static void setLikeAccessibility(@Nullable String accessibility) {
        if (!featureEnabled()) return;
        try {
            syncCurrentVideo();
            long parsed = parseCount(accessibility);
            if (parsed != UNKNOWN) {
                likeCount = parsed;
                Logger.printDebug(() -> "Shorts like count: " + parsed + " (from: " + accessibility + ")");
                maybeSkip();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setLikeAccessibility failure", ex);
        }
    }

    /**
     * Called by ShortsFilter with the comment button accessibility label.
     */
    public static void setCommentAccessibility(@Nullable String accessibility) {
        if (!featureEnabled()) return;
        try {
            syncCurrentVideo();
            long parsed = parseCount(accessibility);
            if (parsed != UNKNOWN) {
                commentCount = parsed;
                Logger.printDebug(() -> "Shorts comment count: " + parsed + " (from: " + accessibility + ")");
                maybeSkip();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setCommentAccessibility failure", ex);
        }
    }

    private static void maybeSkip() {
        if (!featureEnabled()) return;
        if (!ShortsPlayerState.isOpen()) return;
        if (!VideoInformation.lastVideoIdIsShort()) return;

        String videoId = currentVideoId;
        if (videoId.isEmpty() || videoId.equals(skippedVideoId)) return;

        final long minLikes = Settings.SHORTS_MIN_LIKES.get();
        final long minComments = Settings.SHORTS_MIN_COMMENTS.get();

        boolean lowLikes = likeCount != UNKNOWN && likeCount < minLikes;
        boolean lowComments = commentCount != UNKNOWN && commentCount < minComments;

        if (lowLikes || lowComments) {
            skippedVideoId = videoId;
            final long finalLikes = likeCount;
            final long finalComments = commentCount;
            Logger.printDebug(() -> "Skipping low-engagement Short " + videoId
                    + " likes=" + finalLikes + " comments=" + finalComments);
            // Seek slightly past the end so auto-advance triggers.
            Utils.runOnMainThread(() -> {
                long length = VideoInformation.getVideoLength();
                if (length > 0) {
                    VideoInformation.seekTo(length);
                }
            });
        }
    }

    /**
     * Parses a YouTube style abbreviated count into a number.
     * Returns {@link #UNKNOWN} if no number is present.
     *
     * <p>Examples: "1,234 likes" -> 1234, "1.2K" -> 1200, "3.4M comments" -> 3400000.
     * Handles the Danish "t." (thousand) / "mio." (million) via the k/m suffix letters.
     */
    static long parseCount(@Nullable String text) {
        if (text == null || text.isEmpty()) return UNKNOWN;

        Matcher matcher = COUNT_PATTERN.matcher(text);
        if (!matcher.find()) return UNKNOWN;

        String number = matcher.group(1);
        String suffix = matcher.group(2);
        if (number == null) return UNKNOWN;

        // Strip spaces / non-breaking spaces used as grouping separators.
        number = number.replace(" ", "").replace(" ", "").trim();
        if (number.isEmpty()) return UNKNOWN;

        try {
            double multiplier = 1;
            if (suffix != null) {
                switch (Character.toLowerCase(suffix.charAt(0))) {
                    case 'k', 't' -> multiplier = 1_000d;      // 't' = Danish "tusind"
                    case 'm' -> multiplier = 1_000_000d;
                    case 'b' -> multiplier = 1_000_000_000d;
                }
            }

            double value;
            if (multiplier == 1) {
                // No magnitude suffix: separators are thousands groupings, drop them all.
                value = Double.parseDouble(number.replace(".", "").replace(",", ""));
            } else {
                // With a suffix a single separator is the decimal point (e.g. "1.2K", "1,2 t.").
                value = Double.parseDouble(number.replace(",", "."));
            }
            return Math.round(value * multiplier);
        } catch (NumberFormatException ex) {
            Logger.printDebug(() -> "Could not parse count from: " + text);
            return UNKNOWN;
        }
    }
}
