/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.youtube.patches;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

/**
 * Skips low-engagement Shorts (likes below {@link Settings#SHORTS_MIN_LIKES} or comments below
 * {@link Settings#SHORTS_MIN_COMMENTS}) by seeking to the end of the Short. When autoplay /
 * auto-advance is enabled the reel then advances to the next Short, giving an effective "skip".
 *
 * <p>The like count comes from Return YouTube Dislike (must be enabled). The comment count, when
 * available, is captured from the Shorts action bar buffer.
 */
@SuppressWarnings("unused")
public final class SkipLowEngagementShortsPatch {

    private static final long UNKNOWN = -1L;

    /**
     * Matches the comment count that follows the Shorts comment icon in the action bar buffer,
     * e.g. "yt_delhi_comment_24dp<sep>276" or "..._comment_24dp<sep>1.2K".
     */
    private static final Pattern COMMENT_COUNT_PATTERN =
            Pattern.compile("comment_24dp[\\s\\S]{0,3}?(\\d[\\d.,]*)\\s?([KkMmBb])?");

    private static volatile String currentVideoId = "";
    private static volatile long likeCount = UNKNOWN;
    private static volatile long commentCount = UNKNOWN;

    /**
     * Reference to a view inside the Shorts player, used to locate YouTube's own
     * "Next Video" button ({@code reel_next_reel_button}) to advance the reel.
     */
    private static WeakReference<View> reelPlayerViewRef = new WeakReference<>(null);
    private static int nextButtonId;
    private static int reelRecyclerId;

    /**
     * Injection point. Called with the Shorts player view (R.id.reel_watch_player).
     */
    public static void setReelPlayerView(View view) {
        reelPlayerViewRef = new WeakReference<>(view);
    }

    /**
     * Video id that has already been skipped, so we only seek once per Short.
     */
    private static volatile String skippedVideoId = "";

    private static boolean featureEnabled() {
        return Settings.SKIP_LOW_ENGAGEMENT_SHORTS.get();
    }

    private static void syncCurrentVideo(String videoId) {
        if (videoId != null && !videoId.equals(currentVideoId)) {
            currentVideoId = videoId;
            likeCount = UNKNOWN;
            commentCount = UNKNOWN;
        }
    }

    /**
     * Injection point. Called by Return YouTube Dislike when it has the like count for a Short.
     */
    public static void onShortLikeCount(@Nullable String videoId, long likes) {
        if (!featureEnabled() || videoId == null) return;
        try {
            syncCurrentVideo(videoId);
            likeCount = likes;
            Logger.printDebug(() -> "Short " + videoId + " like count: " + likes);
            maybeSkip(videoId);
        } catch (Exception ex) {
            Logger.printException(() -> "onShortLikeCount failure", ex);
        }
    }

    /**
     * Called by ShortsFilter with the comment count parsed from the action bar, when available.
     */
    public static void onShortCommentCount(@Nullable String videoId, long comments) {
        if (!featureEnabled() || videoId == null) return;
        try {
            syncCurrentVideo(videoId);
            commentCount = comments;
            Logger.printDebug(() -> "Short " + videoId + " comment count: " + comments);
            maybeSkip(videoId);
        } catch (Exception ex) {
            Logger.printException(() -> "onShortCommentCount failure", ex);
        }
    }

    /**
     * Called by ShortsFilter with the raw action bar buffer, to extract the comment count.
     */
    public static void captureCommentCount(@Nullable String videoId, @Nullable byte[] buffer) {
        if (!featureEnabled() || videoId == null || buffer == null) return;
        try {
            String text = new String(buffer, StandardCharsets.UTF_8);
            Matcher matcher = COMMENT_COUNT_PATTERN.matcher(text);
            if (matcher.find()) {
                long parsed = parseAbbreviated(matcher.group(1), matcher.group(2));
                if (parsed != UNKNOWN) {
                    onShortCommentCount(videoId, parsed);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "captureCommentCount failure", ex);
        }
    }

    /**
     * Parses an abbreviated count like "276", "1.2K", "3.4M" into a number.
     */
    private static long parseAbbreviated(@Nullable String number, @Nullable String suffix) {
        if (number == null || number.isEmpty()) return UNKNOWN;
        try {
            double multiplier = 1;
            if (suffix != null && !suffix.isEmpty()) {
                switch (Character.toLowerCase(suffix.charAt(0))) {
                    case 'k' -> multiplier = 1_000d;
                    case 'm' -> multiplier = 1_000_000d;
                    case 'b' -> multiplier = 1_000_000_000d;
                }
            }
            double value = (multiplier == 1)
                    ? Double.parseDouble(number.replace(".", "").replace(",", "")) // grouped thousands
                    : Double.parseDouble(number.replace(",", ".")); // decimal before a suffix
            return Math.round(value * multiplier);
        } catch (NumberFormatException ex) {
            return UNKNOWN;
        }
    }

    private static void maybeSkip(String videoId) {
        if (!featureEnabled()) return;
        if (!ShortsPlayerState.isOpen()) return;
        if (videoId == null || videoId.isEmpty() || videoId.equals(skippedVideoId)) return;
        if (!videoId.equals(currentVideoId)) return;

        final long minLikes = Settings.SHORTS_MIN_LIKES.get();
        final long minComments = Settings.SHORTS_MIN_COMMENTS.get();

        boolean lowLikes = likeCount != UNKNOWN && likeCount < minLikes;
        boolean lowComments = commentCount != UNKNOWN && commentCount < minComments;

        if (lowLikes || lowComments) {
            skippedVideoId = videoId;
            final long finalLikes = likeCount;
            final long finalComments = commentCount;
            Logger.printDebug(() -> "Skipping low-engagement Short " + videoId
                    + " likes=" + finalLikes + " (min " + minLikes + ")"
                    + " comments=" + finalComments + " (min " + minComments + ")");
            Utils.runOnMainThread(SkipLowEngagementShortsPatch::advanceToNextShort);
        }
    }

    /**
     * Advances to the next Short. Prefers YouTube's own "Next Video" button
     * ({@code reel_next_reel_button}), which is a clean native advance. Falls back to seeking to
     * the end of the video (which triggers auto-advance when autoplay is enabled).
     */
    private static void advanceToNextShort() {
        try {
            View reel = reelPlayerViewRef.get();

            // 1. Preferred: YouTube's own "Next Video" button, when present (a11y controls).
            if (reel != null) {
                if (nextButtonId == 0) {
                    nextButtonId = ResourceUtils.getIdentifier(ResourceType.ID, "reel_next_reel_button");
                }
                if (nextButtonId != 0) {
                    View nextButton = reel.getRootView().findViewById(nextButtonId);
                    if (nextButton != null && nextButton.isEnabled()) {
                        Logger.printDebug(() -> "Advancing via reel_next_reel_button");
                        nextButton.performClick();
                        return;
                    }
                }
            }

            // 2. Proper: scroll the reel RecyclerView pager by one page to the next Short.
            if (scrollReelToNext(reel)) {
                return;
            }

            // 3. Fallback: seek to the end so auto-advance (if enabled) moves to the next Short.
            long length = VideoInformation.getVideoLength();
            if (length > 0) {
                Logger.printDebug(() -> "Advancing via seek-to-end fallback");
                VideoInformation.seekTo(length);
            } else {
                Logger.printDebug(() -> "Could not advance Short (no next button, no pager, unknown length)");
            }
        } catch (Exception ex) {
            Logger.printException(() -> "advanceToNextShort failure", ex);
        }
    }

    /**
     * Finds the reel pager ({@code reel_recycler}, a RecyclerView) and smoothly scrolls it one
     * page down, to the next Short.
     */
    private static boolean scrollReelToNext(@Nullable View reel) {
        if (reel == null) return false;
        if (reelRecyclerId == 0) {
            reelRecyclerId = ResourceUtils.getIdentifier(ResourceType.ID, "reel_recycler");
        }
        if (reelRecyclerId == 0) return false;

        View recycler = reel.getRootView().findViewById(reelRecyclerId);
        if (recycler == null) {
            Logger.printDebug(() -> "reel_recycler not found");
            return false;
        }
        int width = recycler.getWidth();
        int height = recycler.getHeight();
        if (width <= 0 || height <= 0) return false;

        // Synthesize a natural upward swipe on the reel pager. This produces YouTube's own
        // fling-and-snap to the next Short (no obfuscated methods, no autoplay dependency).
        final float x = width / 2f;
        final float startY = height * 0.75f;
        final float endY = height * 0.25f;
        final int steps = 8;
        final int stepMs = 8;
        final long downTime = SystemClock.uptimeMillis();

        try {
            dispatch(recycler, MotionEvent.ACTION_DOWN, x, startY, downTime, downTime);
            for (int i = 1; i <= steps; i++) {
                float y = startY + (endY - startY) * i / steps;
                dispatch(recycler, MotionEvent.ACTION_MOVE, x, y, downTime, downTime + (long) i * stepMs);
            }
            dispatch(recycler, MotionEvent.ACTION_UP, x, endY, downTime, downTime + (long) (steps + 1) * stepMs);
            Logger.printDebug(() -> "Advancing via synthesized swipe on reel_recycler");
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "swipe on reel_recycler failed", ex);
            return false;
        }
    }

    private static void dispatch(View target, int action, float x, float y, long downTime, long eventTime) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        try {
            target.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }
}
