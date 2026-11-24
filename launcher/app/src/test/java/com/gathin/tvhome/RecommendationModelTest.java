package com.gathin.tvhome;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/** The rules that decide which categories and cards a recommendation shelf shows. */
public class RecommendationModelTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final String CONTINUE = RecommendationModel.watchKey(0), NEXT = RecommendationModel.watchKey(1);
    private static final String CHANNEL = RecommendationModel.channelKey(7);

    /** A 100 ms long episode of one series; only the link, engagement, position and weight tell two apart. */
    private RecommendationModel.Item episode(String link, long engaged, long position, int weight) {
        return new RecommendationModel.Item("moonfin", engaged, "Same series", "S1 · E2", null, 16f / 9,
                link, engaged, position, 100, weight);
    }

    /** What the shelf shows for these categories before the owner has chosen any. */
    private List<RecommendationModel.Category> choose(RecommendationModel.Category... categories) {
        List<RecommendationModel.Category> all = Arrays.asList(categories);
        return RecommendationModel.selected(all, RecommendationModel.defaults(all), NOW);
    }

    /** A finished episode is dropped from Continue watching without taking the unfinished one of the same title. */
    @Test public void completedEpisodeDoesNotSuppressUnfinishedEpisodeWithSameTitle() {
        RecommendationModel.Category category = new RecommendationModel.Category(CONTINUE, "Continue", true,
                Arrays.asList(episode("completed", NOW, 99, 0), episode("unfinished", NOW - 1, 20, 0)));
        assertEquals("unfinished", choose(category).get(0).items.get(0).intent);
    }

    /** Two rows with one link keep the most recently engaged; a different link stays a second card. */
    @Test public void sortsBeforeDeduplicationAndKeepsDifferentEpisodes() {
        RecommendationModel.Category category = new RecommendationModel.Category(CONTINUE, "Continue", true,
                Arrays.asList(episode("same", NOW - 10, 10, 0), episode("different", NOW - 2, 30, 0),
                        episode("same", NOW - 1, 60, 0)));
        List<RecommendationModel.Item> items = choose(category).get(0).items;
        assertEquals(2, items.size());
        assertEquals(60, items.get(0).position);
        assertEquals("different", items.get(1).intent);
    }

    /** The same link in two categories gives a card in each row: deduplication is per category. */
    @Test public void categoriesKeepTheirIdentityEvenWithMatchingTitlesAndLinks() {
        RecommendationModel.Category resume = new RecommendationModel.Category(CONTINUE, "Continue", true,
                Collections.singletonList(episode("episode", NOW, 20, 0)));
        RecommendationModel.Category next = new RecommendationModel.Category(NEXT, "Next episode", true,
                Collections.singletonList(episode("episode", NOW, 0, 0)));
        List<RecommendationModel.Category> result = choose(resume, next);
        assertEquals(2, result.size());
        assertEquals(NEXT, result.get(1).key);
        assertEquals("moonfin", result.get(1).items.get(0).publisher);
    }

    /** An owner who unticked everything sees nothing, even for a channel that is on by default. */
    @Test public void explicitEmptySelectionDoesNotRestoreAllChannels() {
        RecommendationModel.Category category = new RecommendationModel.Category(CHANNEL, "Trending", true,
                Collections.singletonList(episode("video", 0, 0, 1)));
        assertTrue(RecommendationModel.selected(Collections.singletonList(category), Collections.emptySet(), NOW).isEmpty());
    }

    /** A channel the provider does not mark browsable is hidden until the owner ticks it. */
    @Test public void hiddenChannelRequiresExplicitSelection() {
        RecommendationModel.Category category = new RecommendationModel.Category(CHANNEL, "Trending", false,
                Collections.singletonList(episode("video", 0, 0, 1)));
        assertTrue(choose(category).isEmpty());
        assertEquals(1, RecommendationModel.selected(Collections.singletonList(category),
                Collections.singleton(CHANNEL), NOW).size());
    }

    /** A channel's cards come highest published weight first. */
    @Test public void ranksChannelCardsByPublishedWeight() {
        RecommendationModel.Category channel = new RecommendationModel.Category(CHANNEL, "Picks", true,
                Arrays.asList(episode("a", 0, 0, 1), episode("b", 0, 0, 7), episode("c", 0, 0, 5)));
        List<RecommendationModel.Item> cards = choose(channel).get(0).items;
        assertEquals(Arrays.asList("b", "c", "a"), Arrays.asList(cards.get(0).intent, cards.get(1).intent, cards.get(2).intent));
    }

    /** A row keeps ROW_CARDS cards of a longer channel, and the ones it keeps are the heaviest. */
    @Test public void capsARowAtRowCards() {
        List<RecommendationModel.Item> published = new ArrayList<>();
        for (int weight = 0; weight < RecommendationModel.ROW_CARDS + 3; weight++) {
            published.add(episode("link" + weight, 0, 0, weight));
        }
        List<RecommendationModel.Item> cards = choose(
                new RecommendationModel.Category(CHANNEL, "Picks", true, published)).get(0).items;
        assertEquals(RecommendationModel.ROW_CARDS, cards.size());
        assertEquals(3, cards.get(cards.size() - 1).weight);
    }

    /** An entry not engaged with for over 60 days is dropped; a channel item, which has no date, is kept. */
    @Test public void staleResumeItemsAreFilteredButUndatedChannelsRemain() {
        RecommendationModel.Category resume = new RecommendationModel.Category(CONTINUE, "Continue", true,
                Collections.singletonList(episode("old", NOW - 61L * 86400000, 20, 0)));
        RecommendationModel.Category channel = new RecommendationModel.Category(CHANNEL, "Picks", true,
                Collections.singletonList(episode("undated", 0, 0, 1)));
        assertEquals(CHANNEL, choose(resume, channel).get(0).key);
    }

    /** A channel key is told from a Watch Next key, which decides how the row is ranked and named. */
    @Test public void aChannelKeyIsNotAWatchNextKey() {
        assertTrue(new RecommendationModel.Category(CHANNEL, "Picks", true, Collections.emptyList()).isChannel());
        assertFalse(new RecommendationModel.Category(CONTINUE, "Continue", true, Collections.emptyList()).isChannel());
    }
}
