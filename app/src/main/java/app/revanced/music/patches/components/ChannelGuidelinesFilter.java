package app.revanced.music.patches.components;

import android.os.Build;

import androidx.annotation.RequiresApi;

import app.revanced.music.settings.SettingsEnum;


public final class ChannelGuidelinesFilter extends Filter {

    @RequiresApi(api = Build.VERSION_CODES.N)
    public ChannelGuidelinesFilter() {
        pathFilterGroupList.addAll(
                new StringFilterGroup(
                        SettingsEnum.HIDE_CHANNEL_GUIDELINES,
                        "community_guidelines"
                )
        );
    }
}
