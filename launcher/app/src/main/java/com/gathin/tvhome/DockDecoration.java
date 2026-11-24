package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.function.IntSupplier;

/** Draws the tvOS style lighter band behind the first (dock) row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    /** Extra band below the tiles for their names (always_show_names), in px. */
    private final float namesRoom;
    /** Light glass like the top bar's pills (config.xml dock_glass): a white tint that fades downwards. */
    private final boolean lightGlass;
    /** Padding all round the tiles equal to the gap between them (config.xml dock_even_padding). */
    private final boolean evenPadding;
    /** The band's top and bottom the glass gradient was made for; it is remade when they move. */
    private float shaderTop = Float.NaN, shaderBottom = Float.NaN;
    /** Draw the band and its edge (config.xml dock_band); false leaves the tiles floating on the wallpaper. */
    private final boolean bandVisible;
    /** single_row: the tiles after the dock share its row, so they take the same offsets to stay level with it. */
    private final boolean singleRow;
    /** dock_band_all: in a single row, the band runs behind every app instead of the dock apps only. */
    private final boolean bandAll;
    /**
     * In a single row whose band covers the dock apps only, leave the band's right end open and fade it out
     * under the first app after the dock, instead of closing it with a rounded end against that app.
     */
    private final boolean openEnd;
    /** Erases the open end: transparent at 0, opaque at 1, stretched over the fade by fadeSpan. */
    private final Paint fade = new Paint();
    private final Matrix fadeSpan = new Matrix();
    /** The edge's own colour, kept for {@link #litEdge} to blend from. */
    private final int plainEdge;
    /** The glow behind the dock, as its colour with its strength in the alpha byte; null leaves the edge plain. */
    private final IntSupplier glow;

    /**
     * @param translucent the cinematic shelf's band: the artwork and the glow show through it.
     * @param names       the dock tiles show their names, so the band reaches down over them.
     * @param opacity     the plain band's opacity in percent (dock_opacity), so a wallpaper can show through it.
     * @param lightGlass  a light frosted band like the pills, for a sky or wallpaper behind it.
     * @param evenPadding the band's padding is the gap between the tiles on all four sides (dock_even_padding).
     * @param bandVisible false skips the band and its edge, so the tiles float on the wallpaper (dock_band).
     * @param singleRow   every app sits in the dock's row (single_row); the band covers the dock apps only, unless bandAll.
     * @param bandAll     with singleRow, the band runs behind every app in the row (dock_band_all).
     * @param openEnd     with singleRow and not bandAll, the band's right end is open and fades out past the dock.
     * @param glow        read at every draw: the glow behind the dock, its strength in the alpha byte, for the
     *                    band's edge to take its hue; null leaves the edge plain.
     */
    DockDecoration(int dockSize, float density, boolean translucent, boolean names, int opacity, boolean lightGlass,
                   boolean evenPadding, boolean bandVisible, boolean singleRow, boolean bandAll, boolean openEnd,
                   IntSupplier glow) {
        this.glow = glow;
        this.dockSize = dockSize;
        this.singleRow = singleRow;
        this.bandAll = singleRow && bandAll;
        this.openEnd = singleRow && !bandAll && openEnd;
        fade.setShader(new LinearGradient(0, 0, 1, 0, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        this.density = density;
        this.namesRoom = names ? 22 * density : 0;
        this.lightGlass = lightGlass;
        this.evenPadding = evenPadding;
        this.bandVisible = bandVisible;
        band.setColor(translucent ? 0x8C121014 : (Math.round(opacity * 2.55f) << 24) | 0x141416);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        plainEdge = lightGlass ? 0x38FFFFFF : translucent ? 0x26FFFFFF : 0x1FFFFFFF;
        edge.setColor(plainEdge);
    }

    /**
     * The band's edge as if lit by the glow behind it: the glow's own hue, pale (HSV saturation 0.35, value
     * 0.87) at 55% opacity, blended in from the plain edge as the glow comes up. A grey glow has no hue to
     * lend and leaves the edge plain.
     *
     * @param plain the edge's own ARGB colour
     * @param glow  the glow's RGB colour, with its strength (the glow view's alpha) in the alpha byte
     */
    static int litEdge(int plain, int glow) {
        int strength = glow >>> 24;
        int[] rgb = {(glow >> 16) & 0xFF, (glow >> 8) & 0xFF, glow & 0xFF};
        int max = Math.max(rgb[0], Math.max(rgb[1], rgb[2])), min = Math.min(rgb[0], Math.min(rgb[1], rgb[2]));
        if (strength == 0 || max - min < 0.15f * max) return plain;
        int lit = 0x8C;   // 55% opacity
        for (int channel : rgb) {
            lit = lit << 8 | Math.round(0.87f * 255 * (1 - 0.35f * (max - channel) / (max - min)));
        }
        int edge = 0;
        for (int shift = 24; shift >= 0; shift -= 8) {
            int from = (plain >> shift) & 0xFF, to = (lit >> shift) & 0xFF;
            edge |= (from + (to - from) * strength / 255) << shift;
        }
        return edge;
    }

    /** Concentric corners: the band's radius is the tiles' radius plus the band's padding around them. */
    static float radiusPx(float tileRadiusPx, float paddingPx) {
        return tileRadiusPx + paddingPx;
    }

    /**
     * The band's padding on every side for dock_even_padding: the gap between two dock tiles (a cell minus its tile, as
     * AppAdapter.tileWidthPx lays them out), capped by the room the grid leaves at its edges.
     */
    static float evenPaddingPx(int innerWidthPx, int columns, int tileWidthPx, float roomPx) {
        float gap = columns > 0 ? innerWidthPx / (float) columns - tileWidthPx : 0f;
        return Math.max(0f, Math.min(gap, roomPx));
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && (singleRow || pos < dockSize)) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (!bandVisible) return;
        boolean any = false;
        int cardWidth = 0;
        // The last dock tile's right edge and its cell's width, when it is on screen: where an open end fades.
        float tail = Float.NaN, cell = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || (!bandAll && pos >= dockSize)) continue;
            RecyclerView.ViewHolder vh = parent.getChildViewHolder(child);
            View card = vh instanceof AppAdapter.Holder ? ((AppAdapter.Holder) vh).card : child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            cardWidth = card.getWidth();
            if (pos == dockSize - 1) {
                tail = r;
                cell = child.getWidth();
            }
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        float pad = 14 * density;
        // The band can only draw inside the grid, and the first and last dock tiles sit 12dp in from its
        // edges: a full 14dp at the sides sliced the rounded ends flat (#454). Keep the sides, with the
        // 1dp edge line, inside the grid; the top and bottom have room.
        // 22dp at the sides (the grid's 10dp padding makes room): a focused first or last tile grows about 7dp
        // outwards and still keeps about the band's top margin from its rounded end.
        float sidePad = 22 * density;
        float side = Math.max(0f, Math.min(sidePad, Math.min(rect.left, parent.getWidth() - rect.right) - density));
        float radius = AppAdapter.cornerRadius(cardWidth, density);
        if (evenPadding) {
            // The same padding on all four sides, so the band's corners are concentric with the tiles' everywhere.
            int inner = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            // A single row's tiles can run off the screen, so only its top limits the padding; its ends are clamped below.
            float room = (singleRow ? rect.top : Math.min(rect.top, Math.min(rect.left, parent.getWidth() - rect.right))) - density;
            float even = evenPaddingPx(inner, dockSize, cardWidth, room);
            rect.inset(-even, -even);
            radius = radiusPx(radius, even);
        } else {
            // dock_even_padding off: the band as before, with its fixed 26 dp corners.
            rect.inset(singleRow ? -sidePad : -side, -pad);
            radius = 26 * density;
        }
        if (singleRow) {
            // Tiles past the screen's edge: the band stops at the grid's edge with its rounded end instead of being
            // sliced square there.
            rect.left = Math.max(rect.left, density);
            rect.right = Math.min(rect.right, parent.getWidth() - density);
        }
        rect.bottom += namesRoom;
        if (lightGlass && (rect.top != shaderTop || rect.bottom != shaderBottom)) {
            // The pills' glass (pill_bg: white, 18% fading to 8%), a little fainter over this much larger area.
            band.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0x24FFFFFF, 0x0DFFFFFF,
                    Shader.TileMode.CLAMP));
            shaderTop = rect.top;
            shaderBottom = rect.bottom;
        }
        if (glow != null) edge.setColor(litEdge(plainEdge, glow.getAsInt()));
        if (!openEnd || Float.isNaN(tail) || state.getItemCount() <= dockSize) {
            c.drawRoundRect(rect, radius, radius, band);
            c.drawRoundRect(rect, radius, radius, edge);
            return;
        }
        // Open end: the band runs on under the next app and is erased along the way, its rounded end past the fade.
        float end = Math.min(tail + cell, parent.getWidth());
        rect.right = end + radius;
        int layer = c.saveLayer(rect.left - density, rect.top - density, end, rect.bottom + density, null);
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);
        fadeSpan.setScale(end - tail, 1);
        fadeSpan.postTranslate(tail, 0);
        fade.getShader().setLocalMatrix(fadeSpan);
        c.drawRect(tail, rect.top - density, end, rect.bottom + density, fade);
        c.restoreToCount(layer);
    }
}
