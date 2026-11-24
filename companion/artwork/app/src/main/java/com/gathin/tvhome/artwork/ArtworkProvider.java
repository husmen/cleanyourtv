package com.gathin.tvhome.artwork;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.CancellationSignal;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Optional, same-signature image proxy for the launcher's recommendation shelf, which has no INTERNET
 * permission: {@code content://com.gathin.tvhome.artwork/image?url=https://...} streams the image at that
 * address. No activities, boot receiver, storage, polling or service.
 */
public final class ArtworkProvider extends ContentProvider {
    /** Largest image passed on, in bytes; the launcher holds what it reads to the same cap. */
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(6));

    /** Lets the two fetch threads end when idle; nothing else runs until a client reads. */
    @Override public boolean onCreate() {
        workers.allowCoreThreadTimeOut(true);
        return true;
    }

    /** {@link #openFile(Uri, String, CancellationSignal)} for a client that cannot cancel. */
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return openFile(uri, mode, new CancellationSignal());
    }

    /**
     * Starts fetching the {@code url} parameter's image and returns the read end of a pipe it is written to.
     * A failed fetch closes the pipe with an error, so the reader gets an IOException, not a short image.
     *
     * @param uri          {@code /image?url=} with an HTTPS address of at most 8192 characters and no user info
     * @param mode         "r" only
     * @param cancellation cancelling disconnects the fetch and closes the pipe; may be null
     * @throws FileNotFoundException for any other request, or when both workers and the queue are busy
     */
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal cancellation) throws FileNotFoundException {
        CancellationSignal cancel = cancellation == null ? new CancellationSignal() : cancellation;
        cancel.throwIfCanceled();
        String address = uri.getQueryParameter("url");
        if (!"r".equals(mode) || !"/image".equals(uri.getPath()) || address == null || address.length() > 8192) {
            throw new FileNotFoundException("Not an artwork request");
        }
        Uri source = Uri.parse(address);
        if (!"https".equals(source.getScheme()) || source.getHost() == null || source.getUserInfo() != null) {
            throw new FileNotFoundException("Only HTTPS artwork is supported");
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            AtomicReference<HttpURLConnection> active = new AtomicReference<>();
            cancel.setOnCancelListener(() -> {
                HttpURLConnection connection = active.get();
                if (connection != null) connection.disconnect();
                try { pipe[1].closeWithError("Cancelled"); } catch (IOException ignored) { }
            });
            try {
                workers.execute(() -> {
                    try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                        byte[] picture = fetch(address, cancel, active);
                        cancel.throwIfCanceled();
                        out.write(picture);
                    } catch (IOException | RuntimeException ignored) {
                        try { pipe[1].closeWithError("Artwork unavailable"); } catch (IOException ignoredAgain) { }
                    } finally {
                        active.set(null);
                        cancel.setOnCancelListener(null);
                    }
                });
                return pipe[0];
            } catch (RejectedExecutionException busy) {
                cancel.setOnCancelListener(null);
                pipe[0].close();
                pipe[1].close();
                throw new FileNotFoundException("Artwork queue is busy");
            }
        } catch (IOException exception) {
            throw new FileNotFoundException("Artwork unavailable");
        }
    }

    /**
     * Downloads {@code address} within 12 seconds, following up to three redirects by hand so each hop is
     * checked to be HTTPS.
     *
     * @param active holds the open connection, for the cancel listener to disconnect
     * @throws IOException when the response is not a 200 image of at most {@link #MAX_BYTES}, a hop leaves
     *                     HTTPS, or the time runs out
     */
    private static byte[] fetch(String address, CancellationSignal cancel,
                                AtomicReference<HttpURLConnection> active) throws IOException {
        URL url = new URL(address);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        for (int redirects = 0; redirects < 4; redirects++) {
            cancel.throwIfCanceled();
            if (!"https".equals(url.getProtocol())) throw new IOException("HTTPS required");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            active.set(connection);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            try {
                if (System.nanoTime() > deadline) throw new IOException("Artwork deadline reached");
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing redirect");
                    url = new URL(url, location);
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK || connection.getContentLengthLong() > MAX_BYTES) {
                    throw new IOException("Artwork response rejected");
                }
                String type = connection.getContentType();
                if (type != null && !type.startsWith("image/") && !type.startsWith("application/octet-stream")) {
                    throw new IOException("Not an image");
                }
                try (InputStream in = connection.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    for (int count; (count = in.read(buffer)) != -1;) {
                        cancel.throwIfCanceled();
                        if (out.size() + count > MAX_BYTES || System.nanoTime() > deadline) {
                            throw new IOException("Artwork limit reached");
                        }
                        out.write(buffer, 0, count);
                    }
                    return out.toByteArray();
                }
            } finally { active.compareAndSet(connection, null); connection.disconnect(); }
        }
        throw new IOException("Too many redirects");
    }

    // Read-only and file-only: nothing to query, insert, delete or update.
    @Override public String getType(Uri uri) { return "image/*"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
