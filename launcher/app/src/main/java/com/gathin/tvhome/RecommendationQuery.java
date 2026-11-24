package com.gathin.tvhome;

import android.content.ContentResolver;
import android.database.Cursor;
import android.media.tv.TvContract.Channels;
import android.media.tv.TvContract.PreviewPrograms;
import android.media.tv.TvContract.WatchNextPrograms;
import android.os.CancellationSignal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads the Watch Next and preview-channel rows other apps publish in the TV provider. Worker thread only. */
final class RecommendationQuery {
    /** Columns the Watch Next and preview programme tables share. */
    private static final String[] PROGRAM = {PreviewPrograms._ID, PreviewPrograms.COLUMN_PACKAGE_NAME,
            PreviewPrograms.COLUMN_TITLE, PreviewPrograms.COLUMN_EPISODE_TITLE,
            PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER, PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            PreviewPrograms.COLUMN_POSTER_ART_URI, PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
            PreviewPrograms.COLUMN_THUMBNAIL_URI, PreviewPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO,
            PreviewPrograms.COLUMN_INTENT_URI, PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            PreviewPrograms.COLUMN_DURATION_MILLIS, PreviewPrograms.COLUMN_BROWSABLE};

    /**
     * Every publishing app's categories, by package: its four Watch Next types, then its preview channels by
     * name. One pass over each table, since the provider lets a reader without EPG access pass no selection.
     * Continue and Next are on by default; a channel is on when the provider marks it browsable, so the rest
     * stay a choice the owner makes in the app tile's menu.
     *
     * @param watchLabels the four Watch Next row names, in TvContract's type order
     * @throws RuntimeException when the provider is missing, refuses the read or the read is cancelled
     */
    static Map<String, List<RecommendationModel.Category>> load(ContentResolver resolver, CancellationSignal cancel,
                                                                 String[] watchLabels) {
        Map<String, List<List<RecommendationModel.Item>>> watch = new HashMap<>();
        try (Cursor cursor = resolver.query(WatchNextPrograms.CONTENT_URI, extend(PROGRAM,
                WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS),
                null, null, null, cancel)) {
            while (cursor != null && cursor.moveToNext()) {
                cancel.throwIfCanceled();
                int type = (int) number(cursor, WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE);
                RecommendationModel.Item item = item(cursor);
                if (item == null || type < 0 || type >= RecommendationModel.WATCH_TYPES) continue;
                watch.computeIfAbsent(item.publisher, pkg -> {
                    List<List<RecommendationModel.Item>> types = new ArrayList<>();
                    for (int i = 0; i < RecommendationModel.WATCH_TYPES; i++) types.add(new ArrayList<>());
                    return types;
                }).get(type).add(item);
            }
        }
        Map<Long, List<RecommendationModel.Item>> programs = new HashMap<>();
        try (Cursor cursor = resolver.query(PreviewPrograms.CONTENT_URI, extend(PROGRAM,
                PreviewPrograms.COLUMN_CHANNEL_ID, PreviewPrograms.COLUMN_WEIGHT), null, null, null, cancel)) {
            while (cursor != null && cursor.moveToNext()) {
                cancel.throwIfCanceled();
                RecommendationModel.Item item = item(cursor);
                if (item == null) continue;
                programs.computeIfAbsent(number(cursor, PreviewPrograms.COLUMN_CHANNEL_ID), id -> new ArrayList<>())
                        .add(item);
            }
        }
        Map<String, List<RecommendationModel.Category>> channels = new HashMap<>();
        try (Cursor cursor = resolver.query(Channels.CONTENT_URI, new String[]{Channels._ID,
                Channels.COLUMN_PACKAGE_NAME, Channels.COLUMN_DISPLAY_NAME, Channels.COLUMN_TYPE,
                Channels.COLUMN_BROWSABLE}, null, null, null, cancel)) {
            while (cursor != null && cursor.moveToNext()) {
                cancel.throwIfCanceled();
                String publisher = text(cursor, Channels.COLUMN_PACKAGE_NAME);
                String label = text(cursor, Channels.COLUMN_DISPLAY_NAME);
                if (publisher == null || label == null || label.trim().isEmpty()
                        || !Channels.TYPE_PREVIEW.equals(text(cursor, Channels.COLUMN_TYPE))) continue;
                long id = number(cursor, Channels._ID);
                channels.computeIfAbsent(publisher, pkg -> new ArrayList<>()).add(new RecommendationModel.Category(
                        RecommendationModel.channelKey(id), label, number(cursor, Channels.COLUMN_BROWSABLE) != 0,
                        programs.getOrDefault(id, Collections.emptyList())));
            }
        }
        Set<String> publishers = new HashSet<>(watch.keySet());
        publishers.addAll(channels.keySet());
        Map<String, List<RecommendationModel.Category>> catalog = new HashMap<>();
        for (String publisher : publishers) {
            List<RecommendationModel.Category> categories = new ArrayList<>();
            List<List<RecommendationModel.Item>> types = watch.get(publisher);
            for (int type = 0; type < RecommendationModel.WATCH_TYPES; type++) {
                categories.add(RecommendationModel.watchCategory(type, watchLabels[type],
                        types == null ? Collections.emptyList() : types.get(type)));
            }
            List<RecommendationModel.Category> own = channels.getOrDefault(publisher, new ArrayList<>());
            own.sort(Comparator.comparing((RecommendationModel.Category category) -> category.label,
                    String.CASE_INSENSITIVE_ORDER).thenComparing(category -> category.key));
            categories.addAll(own);
            catalog.put(publisher, categories);
        }
        return catalog;
    }

    /**
     * The card for the cursor's programme row, or null when it names no publisher or its publisher hid it.
     * The card image is the poster art, else the thumbnail. A column the table lacks reads as 0: Watch Next
     * has no weight, a preview programme no engagement time.
     */
    private static RecommendationModel.Item item(Cursor c) {
        String publisher = text(c, PreviewPrograms.COLUMN_PACKAGE_NAME);
        if (publisher == null || number(c, PreviewPrograms.COLUMN_BROWSABLE) == 0) return null;
        String title = text(c, PreviewPrograms.COLUMN_TITLE), episode = text(c, PreviewPrograms.COLUMN_EPISODE_TITLE);
        if (title == null || title.trim().isEmpty()) title = episode;
        String season = text(c, PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER);
        String number = text(c, PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER);
        StringBuilder subtitle = new StringBuilder();
        if (season != null && !season.isEmpty()) subtitle.append('S').append(season);
        if (number != null && !number.isEmpty()) subtitle.append(subtitle.length() == 0 ? "E" : " · E").append(number);
        if (episode != null && !episode.equals(title)) subtitle.append(subtitle.length() == 0 ? "" : " · ").append(episode);
        String image = text(c, PreviewPrograms.COLUMN_POSTER_ART_URI);
        String ratio = PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO;
        if (image == null || image.isEmpty()) {
            image = text(c, PreviewPrograms.COLUMN_THUMBNAIL_URI);
            ratio = PreviewPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO;
        }
        return new RecommendationModel.Item(publisher, number(c, PreviewPrograms._ID), title, subtitle.toString(),
                image, aspect((int) number(c, ratio)), text(c, PreviewPrograms.COLUMN_INTENT_URI),
                number(c, WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS),
                number(c, PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS),
                number(c, PreviewPrograms.COLUMN_DURATION_MILLIS), (int) number(c, PreviewPrograms.COLUMN_WEIGHT));
    }

    /** Width over height for one of TvContract's aspect ratio values; 16:9 when unset or unknown. */
    private static float aspect(int ratio) {
        switch (ratio) {
            case PreviewPrograms.ASPECT_RATIO_3_2: return 3f / 2;
            case PreviewPrograms.ASPECT_RATIO_4_3: return 4f / 3;
            case PreviewPrograms.ASPECT_RATIO_1_1: return 1f;
            case PreviewPrograms.ASPECT_RATIO_2_3: return 2f / 3;
            case 5: return 1 / 1.441f; // AndroidX TvContractCompat's ASPECT_RATIO_MOVIE_POSTER
            default: return 16f / 9;
        }
    }

    /** The column's text, or null when the cursor lacks the column or the value is NULL. */
    private static String text(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? null : cursor.getString(index);
    }

    /** The column's number, or 0 when the cursor lacks the column or the value is NULL. */
    private static long number(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? 0 : cursor.getLong(index);
    }

    /** {@code base} followed by {@code extra}. */
    private static String[] extend(String[] base, String... extra) {
        String[] out = Arrays.copyOf(base, base.length + extra.length);
        System.arraycopy(extra, 0, out, base.length, extra.length);
        return out;
    }

    private RecommendationQuery() { }
}
