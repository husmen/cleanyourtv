package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Card artwork, decoded off the main thread and kept in a small cache. The launcher stays offline: an HTTPS
 * address is read through the optional companion's provider, {@code content://} and
 * {@code android.resource://} directly, and anything else keeps the card's placeholder. Main thread only.
 */
final class RecommendationImages {
    static final String AUTHORITY = "com.gathin.tvhome.artwork";
    private static final int CACHE_BYTES = 4 * 1024 * 1024;
    /** The companion's own download cap; a local provider's image is held to it too. */
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    /** Longest side of a decoded bitmap: a 16:9 card's artwork on a 1080p screen. */
    private static final int MAX_PIXELS = 384;
    private final ContentResolver resolver;
    private final PackageManager packages;
    private final String self;
    private final Handler main = new Handler(Looper.getMainLooper());
    // Unbounded: a bounded queue rejected the ninth card of a render and took Home down with it.
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 10, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>());
    private final Map<String, Load> pending = new HashMap<>();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private int generation;
    private boolean closed;

    /** One address being read, and every card waiting for it. */
    private static final class Load {
        final CancellationSignal cancel = new CancellationSignal();
        final List<ImageView> views = new ArrayList<>();
        volatile InputStream stream;
        Future<?> future;
    }

    RecommendationImages(Context context) {
        resolver = context.getContentResolver();
        packages = context.getPackageManager();
        self = context.getPackageName();
        workers.allowCoreThreadTimeOut(true);
    }

    /**
     * Shows the artwork at {@code address} in {@code view} once it is read; the view keeps what it shows until
     * then, and for good when the address cannot be read. Asking again for a view that already has, or is
     * waiting for, this address does nothing, so a row can be requested every time it gains focus.
     */
    void load(String address, ImageView view) {
        if (address == null || closed || address.equals(view.getTag())) return;
        view.setTag(address);
        Bitmap cached = cache.get(address);
        if (cached != null) { show(view, cached); return; }
        Load existing = pending.get(address);
        if (existing != null) { existing.views.add(view); return; }
        Uri source = Uri.parse(address);
        String scheme = source.getScheme();
        if ("https".equals(scheme)) {
            source = new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("image")
                    .appendQueryParameter("url", address).build();
        } else if (!"content".equals(scheme) && !"android.resource".equals(scheme)) return;
        final Uri uri = source;
        final int request = generation;
        Load load = new Load();
        load.views.add(view);
        pending.put(address, load);
        load.future = workers.submit(() -> {
            Bitmap result = decode(uri, load);
            main.post(() -> {
                if (closed || request != generation || pending.get(address) != load) return;
                pending.remove(address);
                if (result == null) return;
                cache.put(address, result);
                for (ImageView target : load.views) {
                    if (address.equals(target.getTag())) show(target, result);
                }
            });
        });
    }

    /** Drops every read still under way; their cards can be asked for again. The cache is kept. */
    void cancel() {
        generation++;
        for (Load load : pending.values()) {
            load.cancel.cancel();
            if (load.future != null) load.future.cancel(true);
            try { if (load.stream != null) load.stream.close(); } catch (IOException ignored) { }
            for (ImageView view : load.views) view.setTag(null);
        }
        pending.clear();
        workers.getQueue().clear();
        main.removeCallbacksAndMessages(null);
    }

    /** {@link #cancel}, and the cache is emptied too: for onTrimMemory. */
    void trim() { cancel(); cache.evictAll(); }

    /** Ends the loader for good; later {@link #load} calls do nothing. */
    void close() {
        closed = true;
        trim();
        workers.shutdownNow();
    }

    private static void show(ImageView view, Bitmap bitmap) {
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImageBitmap(bitmap);
    }

    /**
     * Reads and decodes {@code uri} on a worker, sampled down to {@link #MAX_PIXELS}. Null when it cannot be
     * read: no companion, a companion signed with another key, an unavailable provider, a cancelled read,
     * no network, more than {@link #MAX_BYTES} or not an image.
     */
    private Bitmap decode(Uri uri, Load load) {
        try {
            if (AUTHORITY.equals(uri.getAuthority()) && !companionTrusted()) return null;
            try (AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(uri, "r", load.cancel)) {
                if (descriptor == null) return null;
                try (InputStream stream = descriptor.createInputStream()) {
                    load.stream = stream;
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    while (true) {
                        load.cancel.throwIfCanceled();
                        int read = stream.read(buffer);
                        if (read == -1) break;
                        if (bytes.size() + read > MAX_BYTES) return null;
                        bytes.write(buffer, 0, read);
                    }
                    byte[] data = bytes.toByteArray();
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, options);
                    if (options.outWidth <= 0 || options.outHeight <= 0) return null;
                    options.inSampleSize = 1;
                    while (options.outWidth / options.inSampleSize > MAX_PIXELS
                            || options.outHeight / options.inSampleSize > MAX_PIXELS) options.inSampleSize *= 2;
                    options.inJustDecodeBounds = false;
                    options.inPreferredConfig = Bitmap.Config.RGB_565;
                    load.cancel.throwIfCanceled();
                    return BitmapFactory.decodeByteArray(data, 0, data.length, options);
                } finally { load.stream = null; }
            }
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
            // Another app's provider can throw anything across the binder: keep the placeholder.
            return null;
        }
    }

    /**
     * True when the provider behind {@link #AUTHORITY} is signed with this launcher's key. Without the
     * companion installed any app could claim the authority, and with it the addresses of what is watched.
     */
    private boolean companionTrusted() {
        ProviderInfo provider = packages.resolveContentProvider(AUTHORITY, 0);
        return provider != null
                && packages.checkSignatures(provider.packageName, self) == PackageManager.SIGNATURE_MATCH;
    }
}
