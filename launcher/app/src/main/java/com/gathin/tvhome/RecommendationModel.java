package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Recommendation metadata and the rules that pick what a shelf shows. No Android dependencies. */
final class RecommendationModel {
    /** Cards kept per row; the row scrolls sideways through them. */
    static final int ROW_CARDS = 10;
    /** Watch Next types, in TvContract's order: continue, next, new, watchlist. */
    static final int WATCH_TYPES = 4;
    private static final String WATCH = "watch:", CHANNEL = "channel:";
    private static final long STALE_MILLIS = 60L * 24 * 60 * 60 * 1000;
    private static final int WATCHED_PERCENT = 95;

    /** The saved-choice key of a Watch Next type (0 to {@link #WATCH_TYPES} - 1). */
    static String watchKey(int type) { return WATCH + type; }

    /**
     * The row of one Watch Next type. Continue and Next are on until the owner chooses otherwise; New and
     * Watchlist are for the owner to turn on.
     *
     * @param type 0 to {@link #WATCH_TYPES} - 1, in TvContract's order
     */
    static Category watchCategory(int type, String label, List<Item> items) {
        return new Category(watchKey(type), label, type < 2, items);
    }

    /** The saved-choice key of the preview channel with this provider row id. */
    static String channelKey(long id) { return CHANNEL + id; }

    /** One published programme: a card. */
    static final class Item {
        final String publisher, title, episode, image, intent;
        /** The artwork's width over its height. */
        final float aspect;
        final long id, engaged, position, duration;
        final int weight;

        /**
         * @param publisher package that published the row and receives the launch
         * @param id        provider row id, the last tie-break when ranking
         * @param episode   season, episode number and episode title in one line; empty when there is none
         * @param image     artwork address, or null
         * @param aspect    the artwork's width over its height
         * @param intent    {@code Intent.toUri} link the publisher asked to be opened with
         * @param engaged   last engagement, UTC milliseconds; 0 when the row has none
         * @param position  playback position in milliseconds
         * @param duration  length in milliseconds
         * @param weight    the publisher's rank within its channel, higher first
         */
        Item(String publisher, long id, String title, String episode, String image, float aspect,
             String intent, long engaged, long position, long duration, int weight) {
            this.publisher = publisher;
            this.id = id;
            this.title = title;
            this.episode = episode;
            this.image = image;
            this.aspect = aspect;
            this.intent = intent;
            this.engaged = engaged;
            this.position = position;
            this.duration = duration;
            this.weight = weight;
        }

        /** Playback progress in percent, or -1 when the row gives no position or length. */
        int progress() {
            return position > 0 && duration > 0 ? (int) Math.min(100, 100.0 * position / duration) : -1;
        }
    }

    /** One row: a Watch Next type or a preview channel, with its items. */
    static final class Category {
        final String key, label;
        /** Shown until the owner saves a choice for the app. */
        final boolean defaultEnabled;
        final List<Item> items;

        /**
         * @param key            {@link #watchKey} or {@link #channelKey}; what a saved choice stores
         * @param defaultEnabled shown until the owner saves a choice for the app
         */
        Category(String key, String label, boolean defaultEnabled, List<Item> items) {
            this.key = key;
            this.label = label;
            this.defaultEnabled = defaultEnabled;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
        }

        /** True for a preview channel, false for a Watch Next type. */
        boolean isChannel() { return key.startsWith(CHANNEL); }
    }

    /**
     * The rows to show: the enabled categories, in order, each cut down to its best {@link #ROW_CARDS} items.
     * Items without a title or link are dropped, as are ones not engaged with for 60 days and finished
     * "continue watching" entries. Watch Next ranks by latest engagement, a channel by published weight.
     * A category left with no item is left out.
     *
     * @param enabled keys of the categories to show
     * @param now     current time, UTC milliseconds
     */
    static List<Category> selected(List<Category> categories, Set<String> enabled, long now) {
        List<Category> result = new ArrayList<>();
        for (Category category : categories) {
            if (!enabled.contains(category.key)) continue;
            List<Item> candidates = new ArrayList<>();
            for (Item item : category.items) {
                if (item.title == null || item.title.trim().isEmpty() || item.intent == null || item.intent.isEmpty()) continue;
                if (item.engaged > 0 && now - item.engaged > STALE_MILLIS) continue;
                if (watchKey(0).equals(category.key) && item.progress() >= WATCHED_PERCENT) continue;
                candidates.add(item);
            }
            Comparator<Item> order = category.isChannel()
                    ? Comparator.comparingInt((Item i) -> i.weight).reversed().thenComparingLong(i -> i.id)
                    : Comparator.comparingLong((Item i) -> i.engaged).reversed().thenComparingLong(i -> i.id);
            candidates.sort(order);
            // Filter and rank before deduplication. Titles identify neither an episode nor a unique asset.
            Set<String> seen = new LinkedHashSet<>();
            List<Item> cards = new ArrayList<>();
            for (Item item : candidates) {
                if (seen.add(item.publisher + "\n" + item.intent)) cards.add(item);
                if (cards.size() == ROW_CARDS) break;
            }
            if (!cards.isEmpty()) result.add(new Category(category.key, category.label, true, cards));
        }
        return Collections.unmodifiableList(result);
    }

    /** The keys shown for an app whose owner has saved no choice yet. */
    static Set<String> defaults(List<Category> categories) {
        Set<String> keys = new LinkedHashSet<>();
        for (Category category : categories) if (category.defaultEnabled) keys.add(category.key);
        return keys;
    }

    private RecommendationModel() { }
}
