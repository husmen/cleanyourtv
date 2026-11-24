package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The focused dock app's recommendations, under a single-row dock parked at the top of the screen
 * (config.xml, recommendation_shelf): the app's name, then one sideways row per Watch Next type and preview
 * channel, each card its artwork with the title under it. Owns the provider read, the owner's saved category
 * choices (made from the app tile's hold-OK menu) and the focus inside the shelf; Back is MainActivity's.
 */
final class RecommendationShelf extends LinearLayout {
    static final int PERMISSION_REQUEST = 731;
    private static final String READ_LISTINGS = "android.permission.READ_TV_LISTINGS";
    /** Layout, in dp: the app name, a row's name, its cards, their artwork. */
    private static final int HEADING_DP = 24, LABEL_DP = 30, CARDS_DP = 160, ART_DP = 108;
    private final Activity activity;
    private final Runnable dock;
    private final SharedPreferences choices;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>());
    private final RecommendationImages images;
    private final String[] watchLabels;
    private final ContentObserver observer;
    private final float density;
    /** The space between the dock's tiles and the shelf, in pixels. */
    private final int gap;
    /** Holds the rows of cards; null while the shelf shows none. */
    private ScrollView scroller;
    /** The focusable views of each row on screen, in order; the action line is a row of one. */
    private final List<List<View>> rows = new ArrayList<>();
    /** The artwork views of each row of cards, in card order. */
    private final List<List<ImageView>> art = new ArrayList<>();
    /** Every publishing app's categories by package; null until the first read comes back. */
    private Map<String, List<RecommendationModel.Category>> catalog;
    private List<RecommendationModel.Category> shown = Collections.emptyList();
    /** The card each row last had focus on: a row is entered where it was left. */
    private int[] rowCard = new int[1];
    private CancellationSignal cancellation;
    private Future<?> query;
    private String publisher, appLabel;
    /** docked: a dock app is selected. stale: the provider changed since the catalog was read. */
    private boolean active, observing, closed, docked, stale = true, failed, pendingRender;
    private int generation, panelTop, panelHeight, focusedRow;
    private AlertDialog chooser;
    private final Runnable reload = this::load;

    /**
     * @param dock  moves the focus back to the dock tile the shelf was entered from
     * @param gapDp the space to leave between the dock's tiles and the shelf, in dp
     */
    RecommendationShelf(Activity activity, Runnable dock, int gapDp) {
        super(activity);
        this.activity = activity;
        this.dock = dock;
        density = getResources().getDisplayMetrics().density;
        gap = dp(gapDp);
        choices = activity.getSharedPreferences("recommendation_categories", Activity.MODE_PRIVATE);
        images = new RecommendationImages(activity);
        watchLabels = new String[]{getResources().getString(R.string.recommendation_continue),
                getResources().getString(R.string.recommendation_next),
                getResources().getString(R.string.recommendation_new),
                getResources().getString(R.string.recommendation_watchlist)};
        worker.allowCoreThreadTimeOut(true);
        setOrientation(VERTICAL);
        setVisibility(GONE);
        observer = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) {
                stale = true;
                schedule();
            }
        };
    }

    /** Home is on screen: watch the provider again and read it, since apps publish while Home is away. */
    void resume() {
        active = true;
        stale = true;
        if (permitted() && !observing) {
            ContentResolver resolver = activity.getContentResolver();
            try {
                resolver.registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, observer);
                resolver.registerContentObserver(TvContract.PreviewPrograms.CONTENT_URI, true, observer);
                resolver.registerContentObserver(TvContract.Channels.CONTENT_URI, true, observer);
                observing = true;
            } catch (RuntimeException unavailable) {
                // No TV provider on this set: registering throws for an app that targets Android 8.
                resolver.unregisterContentObserver(observer);
                failed = true;
            }
        }
        if (publisher == null || !docked) return;
        if (hasFocus()) {
            // Back from a card's app: pause dropped the artwork still loading.
            loadArt(focusedRow);
            loadArt(focusedRow + 1);
        } else {
            render();
        }
        schedule();
    }

    /** Home is leaving the screen: stop reading, loading artwork and watching the provider. */
    void pause() {
        active = false;
        cancelQuery();
        images.cancel();
        main.removeCallbacksAndMessages(null);
        if (observing) activity.getContentResolver().unregisterContentObserver(observer);
        observing = false;
        if (chooser != null) chooser.dismiss();
    }

    /**
     * A dock tile took the focus: show that app's rows under the dock. Hides the shelf instead when the tile
     * has no package or less than one row would fit under it.
     *
     * @param dockBottom the bottom of the dock's tiles, in the root view's coordinates
     */
    void select(String pkg, String label, float dockBottom) {
        int top = Math.round(dockBottom) + gap;
        int height = activity.getWindow().getDecorView().getHeight() - top - dp(16);
        if (closed || pkg == null || height < dp(HEADING_DP + LABEL_DP + CARDS_DP)) { hide(); return; }
        docked = true;
        if (top != panelTop || height != panelHeight) {
            panelTop = top;
            panelHeight = height;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, height);
            params.leftMargin = params.rightMargin = dp(64);
            params.topMargin = top;
            setLayoutParams(params);
        }
        if (!pkg.equals(publisher)) {
            publisher = pkg;
            appLabel = label;
            render();
        } else if (pendingRender || getVisibility() != VISIBLE) {
            render();
        }
        if (stale) schedule();
    }

    /** Nothing in the dock is selected (the app library tile, reordering): take the shelf off the screen. */
    void hide() {
        docked = false;
        cancelQuery();
        images.cancel();
        setVisibility(GONE);
    }

    /** Down from the dock: focus the row the shelf was left on, the one at its top. False with nothing to focus. */
    boolean enter() {
        return getVisibility() == VISIBLE && !rows.isEmpty() && focus(focusedRow);
    }

    /** onTrimMemory: give back the catalog, the cards and the artwork; the next resume reads them again. */
    void trim() {
        cancelQuery();
        images.trim();
        catalog = null;
        stale = true;
        shown = Collections.emptyList();
        removeAllViews();
        scroller = null;
        rows.clear();
        art.clear();
    }

    /** The activity is destroyed: stop for good. */
    void close() {
        closed = true;
        pause();
        images.close();
        worker.shutdownNow();
    }

    private boolean permitted() {
        return activity.checkSelfPermission(READ_LISTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Reads the provider shortly, once for a burst of changes. */
    private void schedule() {
        if (!active || closed || !docked || !permitted()) return;
        main.removeCallbacks(reload);
        main.postDelayed(reload, 300);
    }

    private void cancelQuery() {
        generation++;
        main.removeCallbacks(reload);
        if (cancellation != null) cancellation.cancel();
        if (query != null) query.cancel(true);
        worker.getQueue().clear();
        cancellation = null;
        query = null;
    }

    /** Reads every app's categories on the worker, then shows the selected app's. */
    private void load() {
        if (!active || closed || !docked || !permitted()) return;
        cancelQuery();
        int request = generation;
        CancellationSignal signal = new CancellationSignal();
        cancellation = signal;
        query = worker.submit(() -> {
            Map<String, List<RecommendationModel.Category>> result = null;
            try {
                result = RecommendationQuery.load(activity.getContentResolver(), signal, watchLabels);
            } catch (RuntimeException unreadable) {
                // Another app's provider: a missing one, a refusal and a cancelled read all land here.
                if (signal.isCanceled()) return;
            }
            final Map<String, List<RecommendationModel.Category>> data = result;
            main.post(() -> {
                if (!active || closed || request != generation) return;
                failed = data == null;
                if (failed && hasFocus()) return; // nothing new to put under the remote
                if (!failed) {
                    catalog = data;
                    stale = false;
                }
                if (hasFocus() && !shown.isEmpty()) {
                    pendingRender = true; // never replace the cards under the remote
                    return;
                }
                if (hasFocus()) dock.run(); // the action line is about to go
                render();
            });
        });
    }

    /** An app's categories, shown or not; the four empty Watch Next types for one that has published nothing. */
    private List<RecommendationModel.Category> categories(String pkg) {
        List<RecommendationModel.Category> known = catalog == null ? null : catalog.get(pkg);
        if (known != null) return known;
        List<RecommendationModel.Category> watch = new ArrayList<>();
        for (int type = 0; type < RecommendationModel.WATCH_TYPES; type++) {
            watch.add(RecommendationModel.watchCategory(type, watchLabels[type], Collections.emptyList()));
        }
        return watch;
    }

    /** The category keys the owner chose for an app, or the defaults before any choice. */
    private Set<String> enabled(String pkg, List<RecommendationModel.Category> available) {
        Set<String> saved = choices.getStringSet(pkg, null);
        return saved == null ? RecommendationModel.defaults(available) : new HashSet<>(saved);
    }

    /** Rebuilds the shelf for the selected app. The focus must be outside it. */
    private void render() {
        pendingRender = false;
        images.cancel();
        removeAllViews();
        scroller = null;
        rows.clear();
        art.clear();
        List<RecommendationModel.Category> available = categories(publisher);
        shown = RecommendationModel.selected(available, enabled(publisher, available), System.currentTimeMillis());
        rowCard = new int[Math.max(1, shown.size())];
        focusedRow = 0;
        addView(text(appLabel, 18, Color.WHITE), new LayoutParams(-1, dp(HEADING_DP)));
        if (shown.isEmpty()) {
            int caption = caption();
            if (caption != 0) addView(action(caption), new LayoutParams(-1, dp(44)));
        } else {
            scroller = new ScrollView(activity);
            scroller.setVerticalScrollBarEnabled(false);
            addView(scroller, new LayoutParams(-1, 0, 1));
            LinearLayout panels = new LinearLayout(activity);
            panels.setOrientation(VERTICAL);
            // Room under the last row, so it too can scroll up to the top of the shelf.
            panels.setPadding(0, 0, 0, panelHeight - dp(HEADING_DP + LABEL_DP + CARDS_DP));
            scroller.addView(panels);
            for (RecommendationModel.Category category : shown) panels.addView(row(category, rows.size()));
            loadArt(0);
            loadArt(1);
        }
        setVisibility(VISIBLE);
    }

    /** The line shown in place of rows, or 0 when the app has no row to show and only its name is shown. */
    private int caption() {
        if (!permitted()) return R.string.recommendation_allow;
        if (failed) return R.string.recommendation_retry;
        return catalog == null ? R.string.recommendation_loading : 0;
    }

    /** The line for {@link #caption}; all but "loading" take the focus and act on OK. */
    private View action(int caption) {
        TextView action = text(getResources().getString(caption), 14, 0xFFBBBBBB);
        action.setPadding(dp(10), 0, dp(10), 0);
        if (caption == R.string.recommendation_loading) return action;
        action.setFocusable(true);
        action.setClickable(true);
        action.setBackground(background(false));
        action.setOnFocusChangeListener((v, focused) -> v.setBackground(background(focused)));
        action.setOnClickListener(v -> {
            if (!permitted()) activity.requestPermissions(new String[]{READ_LISTINGS}, PERMISSION_REQUEST);
            else schedule();
        });
        action.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_UP) { dock.run(); return true; }
            return key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_LEFT
                    || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        });
        rows.add(Collections.singletonList(action));
        return action;
    }

    /** One category: its name over a strip of cards that scrolls sideways. */
    private View row(RecommendationModel.Category category, int row) {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(VERTICAL);
        String label = label(category);
        panel.addView(text(label, 16, 0xFFCCCCCC), new LayoutParams(-1, dp(LABEL_DP)));
        HorizontalScrollView strip = new HorizontalScrollView(activity);
        strip.setHorizontalScrollBarEnabled(false);
        panel.addView(strip, new LayoutParams(-1, dp(CARDS_DP)));
        LinearLayout line = new LinearLayout(activity);
        strip.addView(line);
        List<View> cards = new ArrayList<>();
        List<ImageView> pictures = new ArrayList<>();
        for (RecommendationModel.Item item : category.items) {
            ImageView artwork = new ImageView(activity);
            View card = card(item, label, artwork, panel, row, cards.size());
            // The artwork keeps the publisher's aspect ratio at one height; 12dp is the card's side padding.
            LayoutParams params = new LayoutParams(dp(ART_DP * item.aspect + 12), -1);
            params.rightMargin = dp(16);
            line.addView(card, params);
            cards.add(card);
            pictures.add(artwork);
        }
        rows.add(cards);
        art.add(pictures);
        return panel;
    }

    /**
     * One programme: artwork, then its title, episode line and playback progress under it. OK opens it, and
     * the D-pad stays inside the shelf except Up from the first row.
     *
     * @param panel the card's row, scrolled to the top of the shelf when the card takes the focus
     * @param row   the row's position in the shelf
     * @param index the card's position in its row
     */
    private View card(RecommendationModel.Item item, String category, ImageView artwork, View panel,
                      int row, int index) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(VERTICAL);
        card.setPadding(dp(6), dp(3), dp(6), dp(3));
        card.setFocusable(true);
        card.setClickable(true);
        card.setContentDescription(appLabel + ", " + category + ", " + item.title + ", " + item.episode);
        card.setBackground(background(false));
        artwork.setImageResource(android.R.drawable.ic_media_play);
        artwork.setScaleType(ImageView.ScaleType.CENTER);
        artwork.setBackgroundColor(0xFF262626);
        card.addView(artwork, new LayoutParams(-1, dp(ART_DP)));
        LayoutParams titleParams = new LayoutParams(-1, -2);
        titleParams.topMargin = dp(3);
        card.addView(text(item.title, 13, Color.WHITE), titleParams);
        if (!item.episode.isEmpty()) card.addView(text(item.episode, 10, 0xFFCCCCCC));
        if (item.progress() >= 0) {
            ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(100);
            progress.setProgress(item.progress());
            card.addView(progress, new LayoutParams(-1, dp(3)));
        }
        card.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(background(focused));
            if (!focused) return;
            focusedRow = row;
            rowCard[row] = index;
            loadArt(row);
            loadArt(row + 1);
            // The focused row sits at the top, its name showing and the next row peeking in below.
            scroller.smoothScrollTo(0, panel.getTop());
        });
        card.setOnClickListener(v -> launch(item));
        card.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (row == 0) dock.run(); else focus(row - 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (row + 1 < rows.size()) focus(row + 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (index > 0) rows.get(row).get(index - 1).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (index + 1 < rows.get(row).size()) rows.get(row).get(index + 1).requestFocus();
                    return true;
                default:
                    return false;
            }
        });
        return card;
    }

    /** Focuses the row on the card it last had focus on. */
    private boolean focus(int row) {
        return rows.get(row).get(rowCard[row]).requestFocus();
    }

    /** Asks for the artwork of a row of cards; a row past the last, or already asked for, costs nothing. */
    private void loadArt(int row) {
        if (row >= art.size()) return;
        List<RecommendationModel.Item> items = shown.get(row).items;
        for (int i = 0; i < items.size(); i++) images.load(items.get(i).image, art.get(row).get(i));
    }

    /**
     * The category chooser for an app, from its tile's hold-OK menu: tick the Watch Next types and preview
     * channels to show as rows. OK saves the choice, which outlasts a restart. An app that has published
     * nothing yet still offers the four Watch Next types, so its rows can be chosen ahead of time. Without
     * the TV listings permission it asks for that instead.
     *
     * @param label the app's name, for the dialog's title
     */
    void configure(String pkg, String label) {
        if (!permitted()) {
            activity.requestPermissions(new String[]{READ_LISTINGS}, PERMISSION_REQUEST);
            return;
        }
        List<RecommendationModel.Category> options = categories(pkg);
        Set<String> selected = enabled(pkg, options);
        String[] labels = new String[options.size()];
        boolean[] checks = new boolean[options.size()];
        for (int i = 0; i < options.size(); i++) {
            labels[i] = label(options.get(i));
            checks[i] = selected.contains(options.get(i).key);
        }
        chooser = new AlertDialog.Builder(activity).setTitle(getResources().getString(R.string.recommendation_categories, label))
                .setMultiChoiceItems(labels, checks, (dialog, which, checked) -> {
                    if (checked) selected.add(options.get(which).key); else selected.remove(options.get(which).key);
                }).setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    choices.edit().putStringSet(pkg, new HashSet<>(selected)).apply();
                    if (docked && pkg.equals(publisher)) render();
                }).setNegativeButton(android.R.string.cancel, null).create();
        chooser.setOnDismissListener(dialog -> { chooser = null; });
        chooser.show();
    }

    /** A row's name: a preview channel is marked as one, a Watch Next type is named as it is. */
    private String label(RecommendationModel.Category category) {
        return category.isChannel()
                ? getResources().getString(R.string.recommendation_channel, category.label) : category.label;
    }

    /**
     * Opens the item with the link its publisher gave, held to the publisher's own package; when the link
     * cannot be used, opens the app instead.
     */
    private void launch(RecommendationModel.Item item) {
        Intent intent = null;
        try {
            intent = Intent.parseUri(item.intent, Intent.URI_INTENT_SCHEME);
            intent.setSelector(null);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.getComponent() != null && !item.publisher.equals(intent.getComponent().getPackageName())) intent = null;
            else if (intent.getPackage() != null && !item.publisher.equals(intent.getPackage())) intent = null;
            else intent.setPackage(item.publisher);
            if (intent != null && activity.getPackageManager().resolveActivity(intent, 0) != null) {
                activity.startActivity(intent);
                return;
            }
        } catch (Exception ignored) { }
        try {
            Intent fallback = activity.getPackageManager().getLeanbackLaunchIntentForPackage(item.publisher);
            if (fallback == null) fallback = activity.getPackageManager().getLaunchIntentForPackage(item.publisher);
            if (fallback != null) { activity.startActivity(fallback); return; }
        } catch (RuntimeException ignored) { }
        Toast.makeText(activity, R.string.recommendation_cannot_open, Toast.LENGTH_SHORT).show();
    }

    private TextView text(String label, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private GradientDrawable background(boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(focused ? 0xFF393939 : 0xC0181818);
        drawable.setCornerRadius(dp(9));
        if (focused) drawable.setStroke(dp(2), Color.WHITE);
        return drawable;
    }

    private int dp(float value) { return Math.round(value * density); }
}
