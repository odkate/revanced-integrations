package app.revanced.integrations.patches.utils;

import static app.revanced.integrations.returnyoutubedislike.ReturnYouTubeDislike.Vote;
import static app.revanced.integrations.utils.ResourceUtils.identifier;

import android.graphics.Rect;
import android.graphics.drawable.ShapeDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import app.revanced.integrations.patches.video.VideoInformation;
import app.revanced.integrations.returnyoutubedislike.ReturnYouTubeDislike;
import app.revanced.integrations.settings.SettingsEnum;
import app.revanced.integrations.shared.PlayerType;
import app.revanced.integrations.utils.LogHelper;
import app.revanced.integrations.utils.ReVancedUtils;
import app.revanced.integrations.utils.ResourceType;

/**
 * Handles all interaction of UI patch components.
 * <p>
 * Known limitation:
 * Litho based Shorts player can experience temporarily frozen video playback if the RYD fetch takes too long.
 * <p>
 * Temporary work around:
 * Enable app spoofing to version 18.33.40 or older, as that uses a non litho Shorts player.
 * <p>
 * Permanent fix (yet to be implemented), either of:
 * - Modify patch to hook onto the Shorts Litho TextView, and update the dislikes asynchronously.
 * - Find a way to force Litho to rebuild it's component tree
 * (and use that hook to force the shorts dislikes to update after the fetch is completed).
 */
@SuppressWarnings("unused")
public class ReturnYouTubeDislikePatch {

    /**
     * Resource identifier of old UI dislike button.
     */
    private static final int OLD_UI_DISLIKE_BUTTON_RESOURCE_ID
            = identifier("dislike_button", ResourceType.ID);
    /**
     * Replacement text to use for "Dislikes" while RYD is fetching.
     */
    private static final Spannable SHORTS_LOADING_SPAN = new SpannableString("-");
    /**
     * Dislikes TextViews used by Shorts.
     * <p>
     * Multiple TextViews are loaded at once (for the prior and next videos to swipe to).
     * Keep track of all of them, and later pick out the correct one based on their on screen position.
     */
    private static final List<WeakReference<TextView>> shortsTextViewRefs = new ArrayList<>();
    /**
     * Injection point.
     * Whether to use incognito mode.
     */
    public static volatile boolean isIncognito;
    /**
     * RYD data for the current video on screen.
     */
    @Nullable
    private static volatile ReturnYouTubeDislike currentVideoData;

    /**
     * The last litho based Shorts loaded.
     * May be the same value as {@link #currentVideoData}, but usually is the next short to swipe to.
     */
    @Nullable
    private static volatile ReturnYouTubeDislike lastLithoShortsVideoData;
    /**
     * Because the litho Shorts spans are created after ReturnYouTubeDislikeFilterPatch
     * detects the video ids, after the user votes the litho will update
     * but {@link #lastLithoShortsVideoData} is not the correct data to use.
     * If this is true, then instead use {@link #currentVideoData}.
     */
    private static volatile boolean lithoShortsShouldUseCurrentData;
    /**
     * Last video id prefetched. Field is prevent prefetching the same video id multiple times in a row.
     */
    @Nullable
    private static volatile String lastPrefetchedVideoId;
    /**
     * Dislikes text label used by old UI.
     */
    @NonNull
    private static WeakReference<TextView> oldUITextViewRef = new WeakReference<>(null);
    /**
     * Original old UI 'Dislikes' text before patch modifications.
     * Required to reset the dislikes when changing videos and RYD is not available.
     * Set only once during the first load.
     */
    private static Spanned oldUIOriginalSpan;
    /**
     * Replacement span that contains dislike value. Used by {@link #oldUiTextWatcher}.
     */
    @Nullable
    private static Spanned oldUIReplacementSpan;
    /**
     * Old UI dislikes can be set multiple times by YouTube.
     * To prevent reverting changes made here, this listener overrides any future changes YouTube makes.
     */
    private static final TextWatcher oldUiTextWatcher = new TextWatcher() {
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        public void afterTextChanged(Editable s) {
            if (oldUIReplacementSpan == null || oldUIReplacementSpan.toString().equals(s.toString())) {
                return;
            }
            s.replace(0, s.length(), oldUIReplacementSpan); // Causes a recursive call back into this listener
        }
    };
    /**
     * Current regular video rolling number text, if rolling number is in use.
     * This is saved to a field as it's used in every draw() call.
     */
    @Nullable
    private static volatile CharSequence rollingNumberSpan;


    public static void onRYDStatusChange(boolean rydEnabled) {
        if (!rydEnabled) {
            // Must remove all values to protect against using stale data
            // if the user enables RYD while a video is on screen.
            clearData();
        }
    }

    private static void clearData() {
        currentVideoData = null;
        lastLithoShortsVideoData = null;
        lithoShortsShouldUseCurrentData = false;
        // Rolling number text should not be cleared,
        // as it's used if incognito Short is opened/closed
        // while a regular video is on screen.
    }


    //
    // 17.x non litho regular video player.
    //

    private static void updateOldUIDislikesTextView() {
        ReturnYouTubeDislike videoData = currentVideoData;
        if (videoData == null) {
            return;
        }
        TextView oldUITextView = oldUITextViewRef.get();
        if (oldUITextView == null) {
            return;
        }
        oldUIReplacementSpan = videoData.getDislikesSpanForRegularVideo(oldUIOriginalSpan, false, false);
        if (!oldUIReplacementSpan.equals(oldUITextView.getText())) {
            oldUITextView.setText(oldUIReplacementSpan);
        }
    }

    /**
     * Injection point.  Called on main thread.
     * <p>
     * Used when spoofing to 16.x and 17.x versions.
     */
    public static void setOldUILayoutDislikes(int buttonViewResourceId, @Nullable TextView textView) {
        try {
            if (!SettingsEnum.RYD_ENABLED.getBoolean()
                    || buttonViewResourceId != OLD_UI_DISLIKE_BUTTON_RESOURCE_ID
                    || textView == null) {
                return;
            }
            LogHelper.printDebug(ReturnYouTubeDislikePatch.class, "setOldUILayoutDislikes");

            if (oldUIOriginalSpan == null) {
                // Use value of the first instance, as it appears TextViews can be recycled
                // and might contain dislikes previously added by the patch.
                oldUIOriginalSpan = (Spanned) textView.getText();
            }
            oldUITextViewRef = new WeakReference<>(textView);
            // No way to check if a listener is already attached, so remove and add again.
            textView.removeTextChangedListener(oldUiTextWatcher);
            textView.addTextChangedListener(oldUiTextWatcher);

            /**
             * If the patch is changed to include the dislikes button as a parameter to this method,
             * then if the button is already selected the dislikes could be adjusted using
             * {@link ReturnYouTubeDislike#setUserVote(Vote)}
             */

            updateOldUIDislikesTextView();

        } catch (Exception ex) {
            LogHelper.printException(ReturnYouTubeDislikePatch.class, "setOldUILayoutDislikes failure", ex);
        }
    }


    //
    // Litho player for both regular videos and Shorts.
    //

    /**
     * Injection point.
     * <p>
     * For Litho segmented buttons and Litho Shorts player.
     */
    @NonNull
    public static CharSequence onLithoTextLoaded(@NonNull Object conversionContext,
                                                 @Nullable AtomicReference<CharSequence> textRef,
                                                 @NonNull CharSequence original) {
        return onLithoTextLoaded(conversionContext, textRef, original, false);
    }

    /**
     * Injection point.
     * <p>
     * Called when a litho text component is initially created,
     * and also when a Span is later reused again (such as scrolling off/on screen).
     * <p>
     * This method is sometimes called on the main thread, but it usually is called _off_ the main thread.
     * This method can be called multiple times for the same UI element (including after dislikes was added).
     *
     * @param textRef Optional cache reference to the like/dislike char sequence,
     *                 which may or may not be the same as the original span parameter.
     *                 If dislikes are added, the atomic reference must be set to the replacement span.
     * @param original Original char sequence was created or reused by Litho.
     * @param isRollingNumber If the span is for a Rolling Number.
     * @return The original char sequence (if nothing should change), or a replacement char sequence that contains dislikes.
     */
    @NonNull
    private static CharSequence onLithoTextLoaded(@NonNull Object conversionContext,
                                                  @Nullable AtomicReference<CharSequence> textRef,
                                                  @NonNull CharSequence original,
                                                  boolean isRollingNumber) {
        try {
            if (!SettingsEnum.RYD_ENABLED.getBoolean()) {
                return original;
            }

            String conversionContextString = conversionContext.toString();
            final CharSequence replacement;
            if (conversionContextString.contains("|segmented_like_dislike_button.eml|")) {
                // Regular video.
                ReturnYouTubeDislike videoData = currentVideoData;
                if (videoData == null) {
                    return original; // User enabled RYD while a video was on screen.
                }
                if (!(original instanceof Spanned)) {
                    original = new SpannableString(original);
                }
                replacement = videoData.getDislikesSpanForRegularVideo((Spanned) original,
                        true, isRollingNumber);

                // When spoofing between 17.09.xx and 17.30.xx the UI is the old layout
                // but uses litho and the dislikes is "|dislike_button.eml|".
                // But spoofing to that range gives a broken UI layout so no point checking for that.
            } else if (!isRollingNumber && conversionContextString.contains("|shorts_dislike_button.eml|")) {
                // Litho Shorts player.
                if (!SettingsEnum.RYD_SHORTS.getBoolean()) {
                    // Must clear the current video here, otherwise if the user opens a regular video
                    // then opens a litho short (while keeping the regular video on screen), then closes the short,
                    // the original video may show the incorrect dislike value.
                    clearData();
                    return original;
                }
                ReturnYouTubeDislike videoData = lastLithoShortsVideoData;
                if (videoData == null) {
                    // The Shorts litho video id filter did not detect the video id.
                    // This is normal in incognito mode, but otherwise is abnormal.
                    LogHelper.printDebug(ReturnYouTubeDislikePatch.class, "Cannot modify Shorts litho span, data is null");
                    return original;
                }
                // Use the correct dislikes data after voting.
                if (lithoShortsShouldUseCurrentData) {
                    lithoShortsShouldUseCurrentData = false;
                    videoData = currentVideoData;
                    if (videoData == null) {
                        LogHelper.printException(ReturnYouTubeDislikePatch.class, "currentVideoData is null"); // Should never happen
                        return original;
                    }
                    LogHelper.printDebug(ReturnYouTubeDislikePatch.class, "Using current video data for litho span");
                }
                replacement = videoData.getDislikeSpanForShort((Spanned) original);
            } else {
                return original;
            }

            if (textRef != null) {
                textRef.set(replacement);
            }
            return replacement;
        } catch (Exception ex) {
            LogHelper.printException(ReturnYouTubeDislikePatch.class, "onLithoTextLoaded failure", ex);
        }
        return original;
    }


    //
    // Litho Shorts player in the incognito mode.
    //

    /**
     * Injection point.
     * <p>
     * It is used only when fetching the dislikes count of Shorts video in the incognito mode.
     *
     * @param original Original span that was created or reused by Litho.
     * @return The original span (if nothing should change), or a replacement span that contains dislikes.
     */
    public static CharSequence onCharSequenceLoaded(@NonNull Object conversionContext,
                                                    @NonNull CharSequence original) {
        try {
            if (!SettingsEnum.RYD_ENABLED.getBoolean() || !isIncognito) {
                return original;
            }
            if (!SettingsEnum.RYD_SHORTS.getBoolean()) {
                // Must clear the current video here, otherwise if the user opens a regular video
                // then opens a litho short (while keeping the regular video on screen), then closes the short,
                // the original video may show the incorrect dislike value.
                clearData();
                return original;
            }

            String conversionContextString = conversionContext.toString();
            if (!conversionContextString.contains("|shorts_dislike_button.eml|"))
                return original;

            ReturnYouTubeDislike videoData = ReturnYouTubeDislike.getFetchForVideoId(VideoInformation.getVideoId());
            videoData.setVideoIdIsShort(true);
            lastLithoShortsVideoData = videoData;
            lithoShortsShouldUseCurrentData = false;

            return videoData.getDislikeSpanForShort(SHORTS_LOADING_SPAN);
        } catch (Exception ex) {
            LogHelper.printException(ReturnYouTubeDislikePatch.class, "onLithoTextLoaded failure", ex);
        }
        return original;
    }


    //
    // Non litho Shorts player.
    //

    private static void clearRemovedShortsTextViews() {
        shortsTextViewRefs.removeIf(ref -> ref.get() == null);
    }

    /**
     * Injection point.  Called when a Shorts dislike is updated.  Always on main thread.
     * Handles update asynchronously, otherwise Shorts video will be frozen while the UI thread is blocked.
     *
     * @return if RYD is enabled and the TextView was updated.
     */
    public static boolean setShortsDislikes(@NonNull View likeDislikeView) {
        try {
            if (!SettingsEnum.RYD_ENABLED.getBoolean()) {
                return false;
            }
            if (!SettingsEnum.RYD_SHORTS.getBoolean()) {
                // Must clear the data here, in case a new video was loaded while PlayerType
                // suggested the video was not a short (can happen when spoofing to an old app version).
                currentVideoData = null;
                return false;
            }
            LogHelper.printDebug(ReturnYouTubeDislikePatch.class, "setShortsDislikes");

            TextView textView = (TextView) likeDislikeView;
            textView.setText(SHORTS_LOADING_SPAN); // Change 'Dislike' text to the loading text.
            shortsTextViewRefs.add(new WeakReference<>(textView));

            if (likeDislikeView.isSelected() && isShortTextViewOnScreen(textView)) {
                LogHelper.printDebug(ReturnYouTubeDislikePatch.class, "Shorts dislike is already selected");
                ReturnYouTubeDislike videoData = currentVideoData;
                if (videoData != null) videoData.setUserVote(Vote.DISLIKE);
            }

            // For the first short played, the Shorts dislike hook is called after the video id hook.
            // But for most other times this hook is called before the video id (which is not ideal).
            // Must update the TextViews here, and also after the videoId changes.
            updateOnScreenShortsTextViews(false);

            return true;
        } catch (Exception ex) {
            LogHelper.printException(ReturnYouTubeDislikePatch.class, "setShortsDislikes failure", ex);
            return false;
        }
    }

    /**
     * @param forceUpdate if false, then only update the 'loading text views.
     *                    If true, update all on screen text views.
     */
    private static void updateOnScreenShortsTextViews(boolean forceUpdate) {
        try {
            clearRemovedShortsTextViews();
            if (shortsTextViewRefs.isEmpty()) {
                return;
            }
            ReturnYouTubeDislike videoData = currentVideoData;
            if (videoData == null) {
                return;
            }

            LogHelper.printDebug(ReturnYouTubeDislikePatch.class, "updateShortsTextViews");

            Runnable update = () -> {
                Spanned shortsDislikesSpan = videoData.getDislikeSpanForShort(SHORTS_LOADING_SPAN);
                ReVancedUtils.runOnMainThreadNowOrLater(() -> {
                    String videoId = videoData.getVideoId();
                    if (!videoId.equals(VideoInformation.getVideoId())) {
                        // User swiped to new video before fetch completed
                        LogHelper.printDebug(ReturnYouTubeDislikePatch.class, "Ignoring stale dislikes data for short: " + videoId);
                        return;
                    }

                    // Update text views that appear to be visible on screen.
                    // Only 1 will be the actual textview for the current Short,
                    // but discarded and not yet garbage collected views can remain.
                    // So must set the dislike span on all views that match.
                    for (WeakReference<TextView> textViewRef : shortsTextViewRefs) {
                        TextView textView = textViewRef.get();
                        if (textView == null) {
                            continue;
                        }
                        if (isShortTextViewOnScreen(textView)
                                && (forceUpdate || textView.getText().toString().equals(SHORTS_LOADING_SPAN.toString()))) {
                            LogHelper.printDebug(ReturnYouTubeDislikePatch.class, "Setting Shorts TextView to: " + shortsDislikesSpan);
                            textView.setText(shortsDislikesSpan);
                        }
                    }
                });
            };
            if (videoData.fetchCompleted()) {
                update.run(); // Network call is completed, no need to wait on background thread.
            } else {
                ReVancedUtils.runOnBackgroundThread(update);
            }
        } catch (Exception ex) {
            LogHelper.printException(ReturnYouTubeDislikePatch.class, "updateOnScreenShortsTextViews failure", ex);
        }
    }

    /**
     * Check if a view is within the screen bounds.
     */
    private static boolean isShortTextViewOnScreen(@NonNull View view) {
        final int[] location = new int[2];
        view.getLocationInWindow(location);
        if (location[0] <= 0 && location[1] <= 0) { // Lower bound
            return false;
        }
        Rect windowRect = new Rect();
        view.getWindowVisibleDisplayFrame(windowRect); // Upper bound
        return location[0] < windowRect.width() && location[1] < windowRect.height();
    }


    //
    // Rolling Number
    //

    /**
     * Injection point.
     */
    public static String onRollingNumberLoaded(@NonNull Object conversionContext,
                                               @NonNull String original) {
        try {
            if (SettingsEnum.RYD_ENABLED.getBoolean()) {
                CharSequence replacement = onLithoTextLoaded(conversionContext, null, original, true);
                if (!replacement.toString().equals(original)) {
                    rollingNumberSpan = replacement;
                    return replacement.toString();
                } // Else, the text was not a likes count but instead the view count or something else.
            }
        } catch (Exception ex) {
            LogHelper.printException(ReturnYouTubeDislikePatch.class, "onRollingNumberLoaded failure", ex);
        }
        return original;
    }

    /**
     * Remove Rolling Number text view modifications made by this patch.
     * Required as it appears text views can be reused for other rolling numbers (view count, upload time, etc).
     */
    private static void removeRollingNumberPatchChanges(TextView view) {
        if (view.getCompoundDrawablePadding() != 0) {
            LogHelper.printDebug(ReturnYouTubeDislikePatch.class, "Removing rolling number styling from TextView");
            view.setCompoundDrawablePadding(0);
            view.setCompoundDrawables(null, null, null, null);
            view.setGravity(Gravity.NO_GRAVITY);
            view.setTextAlignment(View.TEXT_ALIGNMENT_INHERIT);
            view.setSingleLine(false);
        }
    }

    /**
     * Injection point.
     */
    public static CharSequence updateRollingNumber(TextView view, CharSequence original) {
        try {
            if (!SettingsEnum.RYD_ENABLED.getBoolean()) {
                removeRollingNumberPatchChanges(view);
                return original;
            }
            // Called for all instances of RollingNumber, so must check if text is for a dislikes.
            // Text will already have the correct content but it's missing the drawable separators.
            if (!ReturnYouTubeDislike.isPreviouslyCreatedSegmentedSpan(original.toString())) {
                // The text is the video view count, upload time, or some other text.
                removeRollingNumberPatchChanges(view);
                return original;
            }

            CharSequence replacement = rollingNumberSpan;
            if (replacement == null) {
                // User enabled RYD while a video was open,
                // or user opened/closed a Short while a regular video was opened.
                LogHelper.printDebug(ReturnYouTubeDislikePatch.class, "Cannot update rolling number (field is null");
                removeRollingNumberPatchChanges(view);
                return original;
            }

            // TextView does not display the tall left separator correctly,
            // as it goes outside the height bounds and messes up the layout.
            // Fix this by applying the left separator as a text view compound drawable.
            // This create a new issue as the compound drawable is not taken into the
            // layout width sizing, but that is fixed in the span itself where it uses a blank
            // padding string that adds to the layout width but is later ignored during UI drawing.
            if (SettingsEnum.RYD_COMPACT_LAYOUT.getBoolean()) {
                // Do not apply any TextView changes, and text should always fit without clipping.
                removeRollingNumberPatchChanges(view);
            } else if (view.getCompoundDrawablePadding() == 0) {
                // YouTube Rolling Numbers do not use compound drawables or drawable padding.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Single line mode prevents entire words from being entirely clipped,
                    // and instead only clips the portion of text that runs off.
                    // The text should not clip due to the empty end padding,
                    // but use the feature if available just in case.
                    // If making changes to anything, comment this out and verify layout is still correct.
                    view.setSingleLine(true);
                }
                // Center align to distribute the horizontal padding.
                view.setGravity(Gravity.CENTER);
                view.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                ShapeDrawable shapeDrawable = ReturnYouTubeDislike.getLeftSeparatorDrawable();
                view.setCompoundDrawables(shapeDrawable, null, null, null);
                view.setCompoundDrawablePadding(ReturnYouTubeDislike.leftSeparatorShapePaddingPixels);
            }

            // Remove any padding set by Rolling Number.
            view.setPadding(0, 0, 0, 0);

            // When displaying dislikes, the rolling animation is not visually correct
            // and the dislikes always animate (even though the dislike count has not changed).
            // The animation is caused by an image span attached to the span,
            // and using only the modified segmented span prevents the animation from showing.
            return replacement;
        } catch (Exception ex) {
            LogHelper.printException(ReturnYouTubeDislikePatch.class, "updateRollingNumber failure", ex);
            return original;
        }
    }


    //
    // Video Id and voting hooks (all players).
    //

    /**
     * Injection point.  Uses 'playback response' video id hook to preload RYD.
     */
    public static void preloadVideoId(@NonNull String videoId, boolean videoIsOpeningOrPlaying) {
        try {
            // Shorts shelf in home and subscription feed causes player response hook to be called,
            // and the 'is opening/playing' parameter will be false.
            // This hook will be called again when the Short is actually opened.
            if (!videoIsOpeningOrPlaying || !SettingsEnum.RYD_ENABLED.getBoolean()) {
                return;
            }
            if (!SettingsEnum.RYD_SHORTS.getBoolean() && PlayerType.getCurrent().isNoneHiddenOrSlidingMinimized()) {
                return;
            }
            if (videoId.equals(lastPrefetchedVideoId)) {
                return;
            }
            lastPrefetchedVideoId = videoId;
            LogHelper.printDebug(ReturnYouTubeDislikePatch.class, "Prefetching RYD for video: " + videoId);
            ReturnYouTubeDislike.getFetchForVideoId(videoId);
        } catch (Exception ex) {
            LogHelper.printException(ReturnYouTubeDislikePatch.class, "preloadVideoId failure", ex);
        }
    }

    /**
     * Injection point.  Uses 'current playing' video id hook.  Always called on main thread.
     */
    public static void newVideoLoaded(@NonNull String videoId) {
        try {
            if (!SettingsEnum.RYD_ENABLED.getBoolean()) return;
            Objects.requireNonNull(videoId);

            PlayerType currentPlayerType = PlayerType.getCurrent();
            final boolean isNoneHiddenOrSlidingMinimized = currentPlayerType.isNoneHiddenOrSlidingMinimized();
            if (isNoneHiddenOrSlidingMinimized && !SettingsEnum.RYD_SHORTS.getBoolean()) {
                // Must clear here, otherwise the wrong data can be used for a minimized regular video.
                clearData();
                return;
            }

            if (videoIdIsSame(currentVideoData, videoId)) {
                return;
            }
            LogHelper.printDebug(ReturnYouTubeDislikePatch.class, "New video id: " + videoId + " playerType: " + currentPlayerType);

            ReturnYouTubeDislike data = ReturnYouTubeDislike.getFetchForVideoId(videoId);
            // Pre-emptively set the data to short status.
            // Required to prevent Shorts data from being used on a minimized video in incognito mode.
            if (isNoneHiddenOrSlidingMinimized) {
                data.setVideoIdIsShort(true);
            }
            currentVideoData = data;

            // Current video id hook can be called out of order with the non litho Shorts text view hook.
            // Must manually update again here.
            if (isNoneHiddenOrSlidingMinimized) {
                updateOnScreenShortsTextViews(true);
            }
        } catch (Exception ex) {
            LogHelper.printException(ReturnYouTubeDislikePatch.class, "newVideoLoaded failure", ex);
        }
    }

    public static void setLastLithoShortsVideoId(@Nullable String videoId) {
        if (videoIdIsSame(lastLithoShortsVideoData, videoId)) {
            return;
        }
        if (videoId == null) {
            // Litho filter did not detect the video id.  App is in incognito mode,
            // or the proto buffer structure was changed and the video id is no longer present.
            // Must clear both currently playing and last litho data otherwise the
            // next regular video may use the wrong data.
            LogHelper.printDebug(ReturnYouTubeDislikePatch.class, "Litho filter did not find any video ids");
            clearData();
            return;
        }
        LogHelper.printDebug(ReturnYouTubeDislikePatch.class, "New litho Shorts video id: " + videoId);
        ReturnYouTubeDislike videoData = ReturnYouTubeDislike.getFetchForVideoId(videoId);
        videoData.setVideoIdIsShort(true);
        lastLithoShortsVideoData = videoData;
        lithoShortsShouldUseCurrentData = false;
    }

    private static boolean videoIdIsSame(@Nullable ReturnYouTubeDislike fetch, @Nullable String videoId) {
        return (fetch == null && videoId == null)
                || (fetch != null && fetch.getVideoId().equals(videoId));
    }

    /**
     * Injection point.
     * <p>
     * Called when the user likes or dislikes.
     *
     * @param vote int that matches {@link ReturnYouTubeDislike.Vote#value}
     */
    public static void sendVote(int vote) {
        try {
            if (!SettingsEnum.RYD_ENABLED.getBoolean()) {
                return;
            }
            final boolean isNoneHiddenOrMinimized = PlayerType.getCurrent().isNoneHiddenOrMinimized();
            if (isNoneHiddenOrMinimized && !SettingsEnum.RYD_SHORTS.getBoolean()) {
                return;
            }
            ReturnYouTubeDislike videoData = currentVideoData;
            if (videoData == null) {
                LogHelper.printDebug(ReturnYouTubeDislikePatch.class, "Cannot send vote, as current video data is null");
                return; // User enabled RYD while a regular video was minimized.
            }

            for (Vote v : Vote.values()) {
                if (v.value == vote) {
                    videoData.sendVote(v);

                    if (isNoneHiddenOrMinimized) {
                        if (lastLithoShortsVideoData != null) {
                            lithoShortsShouldUseCurrentData = true;
                        }
                        updateOldUIDislikesTextView();
                    }

                    return;
                }
            }
            LogHelper.printException(ReturnYouTubeDislikePatch.class, "Unknown vote type: " + vote);
        } catch (Exception ex) {
            LogHelper.printException(ReturnYouTubeDislikePatch.class, "sendVote failure", ex);
        }
    }
}
