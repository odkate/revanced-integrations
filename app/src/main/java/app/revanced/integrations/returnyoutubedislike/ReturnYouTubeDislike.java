package app.revanced.integrations.returnyoutubedislike;

import static app.revanced.integrations.utils.StringRef.str;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.graphics.drawable.shapes.RectShape;
import android.icu.text.CompactDecimalFormat;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.ReplacementSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.revanced.integrations.returnyoutubedislike.requests.RYDVoteData;
import app.revanced.integrations.returnyoutubedislike.requests.ReturnYouTubeDislikeApi;
import app.revanced.integrations.settings.SettingsEnum;
import app.revanced.integrations.shared.PlayerType;
import app.revanced.integrations.utils.LogHelper;
import app.revanced.integrations.utils.ReVancedUtils;
import app.revanced.integrations.utils.ThemeHelper;

/**
 * Handles fetching and creation/replacing of RYD dislike text spans.
 * <p>
 * Because Litho creates spans using multiple threads, this entire class supports multithreading as well.
 */
public class ReturnYouTubeDislike {

    /**
     * Maximum amount of time to block the UI from updates while waiting for network call to complete.
     * <p>
     * Must be less than 5 seconds, as per:
     * <a href="https://developer.android.com/topic/performance/vitals/anr">...</a>
     */
    private static final long MAX_MILLISECONDS_TO_BLOCK_UI_WAITING_FOR_FETCH = 4000;
    /**
     * How long to retain successful RYD fetches.
     */
    private static final long CACHE_TIMEOUT_SUCCESS_MILLISECONDS = 7 * 60 * 1000; // 7 Minutes
    /**
     * How long to retain unsuccessful RYD fetches,
     * and also the minimum time before retrying again.
     */
    private static final long CACHE_TIMEOUT_FAILURE_MILLISECONDS = 3 * 60 * 1000; // 3 Minutes
    /**
     * Unique placeholder character, used to detect if a segmented span already has dislikes added to it.
     * Must be something YouTube is unlikely to use, as it's searched for in all usage of Rolling Number.
     */
    private static final char MIDDLE_SEPARATOR_CHARACTER = '◎'; // 'bullseye'
    /**
     * Cached lookup of all video ids.
     */
    @GuardedBy("itself")
    private static final Map<String, ReturnYouTubeDislike> fetchCache = new HashMap<>();
    /**
     * Used to send votes, one by one, in the same order the user created them.
     */
    private static final ExecutorService voteSerialExecutor = Executors.newSingleThreadExecutor();
    /**
     * For formatting dislikes as number.
     */
    @GuardedBy("ReturnYouTubeDislike.class") // not thread safe
    private static CompactDecimalFormat dislikeCountFormatter;
    /**
     * For formatting dislikes as percentage.
     */
    @GuardedBy("ReturnYouTubeDislike.class")
    private static NumberFormat dislikePercentageFormatter;
    // Used for segmented dislike spans in Litho regular player.
    private static final Rect leftSeparatorBounds;
    private static final Rect middleSeparatorBounds;

    /**
     * Left separator horizontal padding for Rolling Number layout.
     */
    public static final int leftSeparatorShapePaddingPixels;
    private static final ShapeDrawable leftSeparatorShape;

    static {
        DisplayMetrics dp = Objects.requireNonNull(ReVancedUtils.getContext()).getResources().getDisplayMetrics();

        leftSeparatorBounds = new Rect(0, 0,
                (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.2f, dp),
                (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18, dp));
        final int middleSeparatorSize =
                (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3.7f, dp);
        middleSeparatorBounds = new Rect(0, 0, middleSeparatorSize, middleSeparatorSize);

        leftSeparatorShapePaddingPixels = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10.0f, dp);

        leftSeparatorShape = new ShapeDrawable(new RectShape());
        leftSeparatorShape.setBounds(leftSeparatorBounds);
    }

    private final String videoId;
    /**
     * Stores the results of the vote api fetch, and used as a barrier to wait until fetch completes.
     * Absolutely cannot be holding any lock during calls to {@link Future#get()}.
     */
    private final Future<RYDVoteData> future;
    /**
     * Time this instance and the future was created.
     */
    private final long timeFetched;
    /**
     * If this instance was previously used for a Short.
     */
    @GuardedBy("this")
    private boolean isShort;
    /**
     * Optional current vote status of the UI.  Used to apply a user vote that was done on a previous video viewing.
     */
    @Nullable
    @GuardedBy("this")
    private Vote userVote;
    /**
     * Original dislike span, before modifications.
     */
    @Nullable
    @GuardedBy("this")
    private Spanned originalDislikeSpan;
    /**
     * Replacement like/dislike span that includes formatted dislikes.
     * Used to prevent recreating the same span multiple times.
     */
    @Nullable
    @GuardedBy("this")
    private SpannableString replacementLikeDislikeSpan;

    private ReturnYouTubeDislike(@NonNull String videoId) {
        this.videoId = Objects.requireNonNull(videoId);
        this.timeFetched = System.currentTimeMillis();
        this.future = ReVancedUtils.submitOnBackgroundThread(() -> ReturnYouTubeDislikeApi.fetchVotes(videoId));
    }

    /**
     * @param isSegmentedButton If UI is using the segmented single UI component for both like and dislike.
     */
    @NonNull
    private static SpannableString createDislikeSpan(@NonNull Spanned oldSpannable,
                                                     boolean isSegmentedButton,
                                                     boolean isRollingNumber,
                                                     @NonNull RYDVoteData voteData) {
        if (!isSegmentedButton) {
            // Simple replacement of 'dislike' with a number/percentage.
            return newSpannableWithDislikes(oldSpannable, voteData);
        }

        // Note: Some locales use right to left layout (Arabic, Hebrew, etc).
        // If making changes to this code, change device settings to a RTL language and verify layout is correct.
        String oldLikesString = oldSpannable.toString();

        // YouTube creators can hide the like count on a video,
        // and the like count appears as a device language specific string that says 'Like'.
        // Check if the string contains any numbers.
        if (!stringContainsNumber(oldLikesString)) {
            // Likes are hidden.
            // RYD does not provide usable data for these types of videos,
            // and the API returns bogus data (zero likes and zero dislikes)
            // discussion about this: https://github.com/Anarios/return-youtube-dislike/discussions/530
            //
            // example video: https://www.youtube.com/watch?v=UnrU5vxCHxw
            // RYD data: https://returnyoutubedislikeapi.com/votes?videoId=UnrU5vxCHxw
            //
            // Change the "Likes" string to show that likes and dislikes are hidden.
            String hiddenMessageString = str("revanced_ryd_video_likes_hidden_by_video_owner");
            return newSpanUsingStylingOfAnotherSpan(oldSpannable, hiddenMessageString);
        }

        SpannableStringBuilder builder = new SpannableStringBuilder();
        final boolean compactLayout = SettingsEnum.RYD_COMPACT_LAYOUT.getBoolean();
        final int separatorColor = getSeparatorColor();

        if (!compactLayout) {
            String leftSeparatorString = ReVancedUtils.isRightToLeftTextLayout()
                    ? "\u200F"  // u200F = right to left character
                    : "\u200E"; // u200E = left to right character
            final Spannable leftSeparatorSpan;
            if (isRollingNumber) {
                leftSeparatorSpan = new SpannableString(leftSeparatorString);
            } else {
                leftSeparatorString += "  ";
                leftSeparatorSpan = new SpannableString(leftSeparatorString);
                // Styling spans cannot overwrite RTL or LTR character.
                leftSeparatorSpan.setSpan(new VerticallyCenteredImageSpan(getLeftSeparatorDrawable(), false),
                        1, 2, Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
                leftSeparatorSpan.setSpan(new FixedWidthEmptySpan(leftSeparatorShapePaddingPixels),
                        2, 3, Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
            }
            builder.append(leftSeparatorSpan);
        }

        // likes
        builder.append(newSpanUsingStylingOfAnotherSpan(oldSpannable, oldLikesString));

        // middle separator
        String middleSeparatorString = compactLayout
                ? "  " + MIDDLE_SEPARATOR_CHARACTER + "  "
                : "  \u2009" + MIDDLE_SEPARATOR_CHARACTER + "\u2009  "; // u2009 = 'narrow space' character
        final int shapeInsertionIndex = middleSeparatorString.length() / 2;
        Spannable middleSeparatorSpan = new SpannableString(middleSeparatorString);
        ShapeDrawable shapeDrawable = new ShapeDrawable(new OvalShape());
        shapeDrawable.getPaint().setColor(separatorColor);
        shapeDrawable.setBounds(middleSeparatorBounds);
        // Use original text width if using compact layout with Rolling Number,
        // as there is no empty padding to allow any layout width differences.
        VerticallyCenteredImageSpan verticallyCentered
                = new VerticallyCenteredImageSpan(shapeDrawable, isRollingNumber && compactLayout);
        middleSeparatorSpan.setSpan(verticallyCentered,
                shapeInsertionIndex, shapeInsertionIndex + 1, Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
        builder.append(middleSeparatorSpan);

        // dislikes
        builder.append(newSpannableWithDislikes(oldSpannable, voteData));

        // Add some padding to the right for Rolling Number segmented span.
        // Use a remove space span, as the layout uses the measured text width and not the
        // actual span width.  So adding padding and then removing it while drawing gives some
        // extra wiggle room for the left separator drawable (which is not measured for layout width).
        if (isRollingNumber && !compactLayout) {
            // To test this, set the device system font to the smallest available.
            // And try different system fonts and try on an Android 8 or 9 device
            // (which has no TextView single line mode), or comment
            // out the setSingleLineMode() call in the patch.
            // If text clipping still occurs, then increase the number of padding spaces below.
            Spannable rightPaddingString = new SpannableString("      ");
            rightPaddingString.setSpan(new FixedWidthEmptySpan(0), 0,
                    rightPaddingString.length(), Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
            builder.append(rightPaddingString);
        }

        return new SpannableString(builder);
    }

    private static int getSeparatorColor() {
        return ThemeHelper.getDayNightTheme()
                ? 0x29AAAAAA  // transparent dark gray
                : 0xFFD9D9D9; // light gray
    }

    public static ShapeDrawable getLeftSeparatorDrawable() {
        leftSeparatorShape.getPaint().setColor(getSeparatorColor());
        return leftSeparatorShape;
    }

    /**
     * @return If the text is likely for a previously created likes/dislikes segmented span.
     */
    public static boolean isPreviouslyCreatedSegmentedSpan(@NonNull String text) {
        return text.indexOf(MIDDLE_SEPARATOR_CHARACTER) >= 0;
    }

    /**
     * Correctly handles any unicode numbers (such as Arabic numbers).
     *
     * @return if the string contains at least 1 number.
     */
    private static boolean stringContainsNumber(@NonNull String text) {
        for (int index = 0, length = text.length(); index < length; index++) {
            if (Character.isDigit(text.codePointAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static boolean spansHaveEqualTextAndColor(@NonNull Spanned one, @NonNull Spanned two) {
        // Cannot use equals on the span, because many of the inner styling spans do not implement equals.
        // Instead, compare the underlying text and the text color to handle when dark mode is changed.
        // Cannot compare the status of device dark mode, as Litho components are updated just before dark mode status changes.
        if (!one.toString().equals(two.toString())) {
            return false;
        }
        ForegroundColorSpan[] oneColors = one.getSpans(0, one.length(), ForegroundColorSpan.class);
        ForegroundColorSpan[] twoColors = two.getSpans(0, two.length(), ForegroundColorSpan.class);
        final int oneLength = oneColors.length;
        if (oneLength != twoColors.length) {
            return false;
        }
        for (int i = 0; i < oneLength; i++) {
            if (oneColors[i].getForegroundColor() != twoColors[i].getForegroundColor()) {
                return false;
            }
        }
        return true;
    }

    private static SpannableString newSpannableWithDislikes(@NonNull Spanned sourceStyling, @NonNull RYDVoteData voteData) {
        return newSpanUsingStylingOfAnotherSpan(sourceStyling,
                SettingsEnum.RYD_DISLIKE_PERCENTAGE.getBoolean()
                        ? formatDislikePercentage(voteData.getDislikePercentage())
                        : formatDislikeCount(voteData.getDislikeCount()));
    }

    private static SpannableString newSpanUsingStylingOfAnotherSpan(@NonNull Spanned sourceStyle, @NonNull CharSequence newSpanText) {
        SpannableString destination = new SpannableString(newSpanText);
        Object[] spans = sourceStyle.getSpans(0, sourceStyle.length(), Object.class);
        for (Object span : spans) {
            destination.setSpan(span, 0, destination.length(), sourceStyle.getSpanFlags(span));
        }
        return destination;
    }

    /**
     * @noinspection deprecation
     */
    private static String formatDislikeCount(long dislikeCount) {
        synchronized (ReturnYouTubeDislike.class) { // number formatter is not thread safe, must synchronize
            if (dislikeCountFormatter == null) {
                // Note: Java number formatters will use the locale specific number characters.
                // such as Arabic which formats "1.234" into "۱,۲۳٤"
                // But YouTube disregards locale specific number characters
                // and instead shows english number characters everywhere.
                Locale locale = Objects.requireNonNull(ReVancedUtils.getContext()).getResources().getConfiguration().locale;
                LogHelper.printDebug(ReturnYouTubeDislike.class, "Locale: " + locale);
                dislikeCountFormatter = CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT);
            }
            return dislikeCountFormatter.format(dislikeCount);
        }
    }

    /**
     * @noinspection deprecation
     */
    private static String formatDislikePercentage(float dislikePercentage) {
        synchronized (ReturnYouTubeDislike.class) { // number formatter is not thread safe, must synchronize
            if (dislikePercentageFormatter == null) {
                Locale locale = Objects.requireNonNull(ReVancedUtils.getContext()).getResources().getConfiguration().locale;
                LogHelper.printDebug(ReturnYouTubeDislike.class, "Locale: " + locale);
                dislikePercentageFormatter = NumberFormat.getPercentInstance(locale);
            }
            if (dislikePercentage >= 0.01) { // at least 1%
                dislikePercentageFormatter.setMaximumFractionDigits(0); // show only whole percentage points
            } else {
                dislikePercentageFormatter.setMaximumFractionDigits(1); // show up to 1 digit precision
            }
            return dislikePercentageFormatter.format(dislikePercentage);
        }
    }

    @NonNull
    public static ReturnYouTubeDislike getFetchForVideoId(@Nullable String videoId) {
        Objects.requireNonNull(videoId);
        synchronized (fetchCache) {
            // Remove any expired entries.
            final long now = System.currentTimeMillis();
            fetchCache.values().removeIf(value -> {
                final boolean expired = value.isExpired(now);
                if (expired)
                    LogHelper.printDebug(ReturnYouTubeDislike.class, "Removing expired fetch: " + value.videoId);
                return expired;
            });

            ReturnYouTubeDislike fetch = fetchCache.get(videoId);
            if (fetch == null) {
                fetch = new ReturnYouTubeDislike(videoId);
                fetchCache.put(videoId, fetch);
            }
            return fetch;
        }
    }

    /**
     * Should be called if the user changes settings for dislikes appearance.
     */
    public static void clearAllUICaches() {
        synchronized (fetchCache) {
            for (ReturnYouTubeDislike fetch : fetchCache.values()) {
                fetch.clearUICache();
            }
        }
    }

    private boolean isExpired(long now) {
        final long timeSinceCreation = now - timeFetched;
        if (timeSinceCreation < CACHE_TIMEOUT_FAILURE_MILLISECONDS) {
            return false; // Not expired, even if the API call failed.
        }
        if (timeSinceCreation > CACHE_TIMEOUT_SUCCESS_MILLISECONDS) {
            return true; // Always expired.
        }
        // Only expired if the fetch failed (API null response).
        return (!fetchCompleted() || getFetchData(MAX_MILLISECONDS_TO_BLOCK_UI_WAITING_FOR_FETCH) == null);
    }

    @Nullable
    public RYDVoteData getFetchData(long maxTimeToWait) {
        try {
            return future.get(maxTimeToWait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            LogHelper.printDebug(ReturnYouTubeDislike.class, "Waited but future was not complete after: " + maxTimeToWait + "ms");
        } catch (ExecutionException | InterruptedException ex) {
            LogHelper.printException(ReturnYouTubeDislike.class, "Future failure ", ex); // will never happen
        }
        return null;
    }

    /**
     * @return if the RYD fetch call has completed.
     */
    public boolean fetchCompleted() {
        return future.isDone();
    }

    private synchronized void clearUICache() {
        if (replacementLikeDislikeSpan != null) {
            LogHelper.printDebug(ReturnYouTubeDislike.class, "Clearing replacement span for: " + videoId);
        }
        replacementLikeDislikeSpan = null;
    }

    @NonNull
    public String getVideoId() {
        return videoId;
    }

    /**
     * Pre-emptively set this as a Short.
     */
    public synchronized void setVideoIdIsShort(boolean isShort) {
        this.isShort = isShort;
    }

    /**
     * @return the replacement span containing dislikes, or the original span if RYD is not available.
     */
    @NonNull
    public synchronized Spanned getDislikesSpanForRegularVideo(@NonNull Spanned original,
                                                               boolean isSegmentedButton,
                                                               boolean isRollingNumber) {
        return waitForFetchAndUpdateReplacementSpan(original, isSegmentedButton, isRollingNumber,false);
    }

    /**
     * Called when a Shorts dislike Spannable is created.
     */
    @NonNull
    public synchronized Spanned getDislikeSpanForShort(@NonNull Spanned original) {
        return waitForFetchAndUpdateReplacementSpan(original, false, false, true);
    }

    @NonNull
    private Spanned waitForFetchAndUpdateReplacementSpan(@NonNull Spanned original,
                                                         boolean isSegmentedButton,
                                                         boolean isRollingNumber,
                                                         boolean spanIsForShort) {
        try {
            RYDVoteData votingData = getFetchData(MAX_MILLISECONDS_TO_BLOCK_UI_WAITING_FOR_FETCH);
            if (votingData == null) {
                LogHelper.printDebug(ReturnYouTubeDislike.class, "Cannot add dislike to UI (RYD data not available)");
                return original;
            }

            synchronized (this) {
                if (spanIsForShort) {
                    // Cannot set this to false if span is not for a Short.
                    // When spoofing to an old version and a Short is opened while a regular video
                    // is on screen, this instance can be loaded for the minimized regular video.
                    // But this Shorts data won't be displayed for that call
                    // and when it is un-minimized it will reload again and the load will be ignored.
                    isShort = true;
                } else if (isShort) {
                    // user:
                    // 1, opened a video
                    // 2. opened a short (without closing the regular video)
                    // 3. closed the short
                    // 4. regular video is now present, but the videoId and RYD data is still for the short
                    LogHelper.printDebug(ReturnYouTubeDislike.class, "Ignoring regular video dislike span,"
                            + " as data loaded was previously used for a Short: " + videoId);
                    return original;
                }

                if (originalDislikeSpan != null && replacementLikeDislikeSpan != null) {
                    if (spansHaveEqualTextAndColor(original, replacementLikeDislikeSpan)) {
                        LogHelper.printDebug(ReturnYouTubeDislike.class, "Ignoring previously created dislikes span of data: " + videoId);
                        return original;
                    }
                    if (spansHaveEqualTextAndColor(original, originalDislikeSpan)) {
                        LogHelper.printDebug(ReturnYouTubeDislike.class, "Replacing span with previously created dislike span of data: " + videoId);
                        return replacementLikeDislikeSpan;
                    }
                }
                if (isSegmentedButton && isPreviouslyCreatedSegmentedSpan(original.toString())) {
                    // need to recreate using original, as original has prior outdated dislike values
                    if (originalDislikeSpan == null) {
                        // Should never happen.
                        LogHelper.printDebug(ReturnYouTubeDislike.class, "Cannot add dislikes - original span is null. videoId: " + videoId);
                        return original;
                    }
                    original = originalDislikeSpan;
                }

                // No replacement span exist, create it now.

                if (userVote != null) {
                    votingData.updateUsingVote(userVote);
                }
                originalDislikeSpan = original;
                replacementLikeDislikeSpan = createDislikeSpan(original, isSegmentedButton, isRollingNumber, votingData);
                LogHelper.printDebug(ReturnYouTubeDislike.class, "Replaced: '" + originalDislikeSpan + "' with: '"
                        + replacementLikeDislikeSpan + "'" + " using video: " + videoId);

                return replacementLikeDislikeSpan;
            }
        } catch (Exception e) {
            LogHelper.printException(ReturnYouTubeDislike.class, "waitForFetchAndUpdateReplacementSpan failure", e); // should never happen
        }
        return original;
    }

    public void sendVote(@NonNull Vote vote) {
        ReVancedUtils.verifyOnMainThread();
        Objects.requireNonNull(vote);
        try {
            if (isShort != PlayerType.getCurrent().isNoneOrHidden()) {
                // Shorts was loaded with regular video present, then Shorts was closed.
                // and then user voted on the now visible original video.
                // Cannot send a vote, because this instance is for the wrong video.
                return;
            }

            setUserVote(vote);

            voteSerialExecutor.execute(() -> {
                try { // Must wrap in try/catch to properly log exceptions.
                    ReturnYouTubeDislikeApi.sendVote(videoId, vote);
                } catch (Exception ex) {
                    LogHelper.printException(ReturnYouTubeDislike.class, "Failed to send vote", ex);
                }
            });
        } catch (Exception ex) {
            LogHelper.printException(ReturnYouTubeDislike.class, "Error trying to send vote", ex);
        }
    }

    /**
     * Sets the current user vote value, and does not send the vote to the RYD API.
     * <p>
     * Only used to set value if thumbs up/down is already selected on video load.
     */
    public void setUserVote(@NonNull Vote vote) {
        Objects.requireNonNull(vote);
        try {
            LogHelper.printDebug(ReturnYouTubeDislike.class, "setUserVote: " + vote);

            synchronized (this) {
                userVote = vote;
                clearUICache();
            }

            if (future.isDone()) {
                // Update the fetched vote data.
                RYDVoteData voteData = getFetchData(MAX_MILLISECONDS_TO_BLOCK_UI_WAITING_FOR_FETCH);
                if (voteData == null) {
                    // RYD fetch failed.
                    LogHelper.printDebug(ReturnYouTubeDislike.class, "Cannot update UI (vote data not available)");
                    return;
                }
                voteData.updateUsingVote(vote);
            } // Else, vote will be applied after fetch completes.

        } catch (Exception ex) {
            LogHelper.printException(ReturnYouTubeDislike.class, "setUserVote failure", ex);
        }
    }

    public enum Vote {
        LIKE(1),
        DISLIKE(-1),
        LIKE_REMOVE(0);

        public final int value;

        Vote(int value) {
            this.value = value;
        }
    }
}

/**
 * Styles a Spannable with an empty fixed width.
 */
class FixedWidthEmptySpan extends ReplacementSpan {
    final int fixedWidth;
    /**
     * @param fixedWith Fixed width in screen pixels.
     */
    FixedWidthEmptySpan(int fixedWith) {
        this.fixedWidth = fixedWith;
        if (fixedWith < 0) throw new IllegalArgumentException();
    }
    @Override
    public int getSize(@NonNull Paint paint, @NonNull CharSequence text,
                       int start, int end, @Nullable Paint.FontMetricsInt fontMetrics) {
        return fixedWidth;
    }
    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                     float x, int top, int y, int bottom, @NonNull Paint paint) {
        // Nothing to draw.
    }
}

/**
 * Vertically centers a Spanned Drawable.
 */
class VerticallyCenteredImageSpan extends ImageSpan {
    final boolean useOriginalWidth;

    /**
     * @param useOriginalWidth Use the original layout width of the text this span is applied to,
     * and not the bounds of the Drawable. Drawable is always displayed using it's own bounds,
     * and this setting only affects the layout width of the entire span.
     */
    public VerticallyCenteredImageSpan(Drawable drawable, boolean useOriginalWidth) {
        super(drawable);
        this.useOriginalWidth = useOriginalWidth;
    }

    @Override
    public int getSize(@NonNull Paint paint, @NonNull CharSequence text,
                       int start, int end, @Nullable Paint.FontMetricsInt fontMetrics) {
        Drawable drawable = getDrawable();
        Rect bounds = drawable.getBounds();
        if (fontMetrics != null) {
            Paint.FontMetricsInt paintMetrics = paint.getFontMetricsInt();
            final int fontHeight = paintMetrics.descent - paintMetrics.ascent;
            final int drawHeight = bounds.bottom - bounds.top;
            final int halfDrawHeight = drawHeight / 2;
            final int yCenter = paintMetrics.ascent + fontHeight / 2;

            fontMetrics.ascent = yCenter - halfDrawHeight;
            fontMetrics.top = fontMetrics.ascent;
            fontMetrics.bottom = yCenter + halfDrawHeight;
            fontMetrics.descent = fontMetrics.bottom;
        }
        if (useOriginalWidth) {
            return (int) paint.measureText(text, start, end);
        }
        return bounds.right;
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                     float x, int top, int y, int bottom, @NonNull Paint paint) {
        Drawable drawable = getDrawable();
        canvas.save();
        Paint.FontMetricsInt paintMetrics = paint.getFontMetricsInt();
        final int fontHeight = paintMetrics.descent - paintMetrics.ascent;
        final int yCenter = y + paintMetrics.descent - fontHeight / 2;
        final Rect drawBounds = drawable.getBounds();
        float translateX = x;
        if (useOriginalWidth) {
            // Horizontally center the drawable in the same space as the original text.
            translateX += (paint.measureText(text, start, end) - (drawBounds.right - drawBounds.left)) / 2;
        }
        final int translateY = yCenter - (drawBounds.bottom - drawBounds.top) / 2;
        canvas.translate(translateX, translateY);
        drawable.draw(canvas);
        canvas.restore();
    }
}