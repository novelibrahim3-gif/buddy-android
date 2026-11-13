package app.buddy.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Coordinate-only, non-touchable application overlay for Accessibility-off point_out. */
final class RootMarksOverlay {
    static final class Mark {
        final int x, y;
        final String label;

        Mark(int x, int y, String label) {
            this.x = x;
            this.y = y;
            this.label = label == null ? "" : label.trim();
        }
    }

    private final Context app;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private View view;
    private Runnable expiry;
    private boolean hidden;

    RootMarksOverlay(Context context) {
        app = context.getApplicationContext();
        wm = app.getSystemService(WindowManager.class);
    }

    void show(List<Mark> marks, String style, long durationMs) throws Exception {
        onMain(() -> {
            clearNow();
            View next = new MarksView(app, marks, style);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setFitInsetsTypes(0);
            lp.setTitle("Buddy point_out");
            if (hidden) next.setVisibility(View.GONE);
            wm.addView(next, lp);
            view = next;
            expiry = this::clearNow;
            main.postDelayed(expiry, Math.max(1000, durationMs));
        });
    }

    void clear() throws Exception {
        onMain(this::clearNow);
    }

    void setHidden(boolean on) throws Exception {
        onMain(() -> {
            hidden = on;
            if (view != null) view.setVisibility(on ? View.GONE : View.VISIBLE);
        });
    }

    private void clearNow() {
        if (expiry != null) {
            main.removeCallbacks(expiry);
            expiry = null;
        }
        if (view != null) {
            try {
                wm.removeView(view);
            } catch (Exception ignored) {
            }
            view = null;
        }
    }

    private void onMain(Runnable action) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        main.post(() -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out updating the point_out overlay.");
        if (failure.get() != null) throw failure.get();
    }

    private static final class MarksView extends View {
        private final List<Mark> marks;
        private final String style;
        private final float d;
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint labelText = new TextPaint(Paint.ANTI_ALIAS_FLAG);

        MarksView(Context context, List<Mark> marks, String style) {
            super(context);
            this.marks = marks;
            this.style = style;
            d = getResources().getDisplayMetrics().density;
            ring.setColor(Color.rgb(0, 180, 255));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(3 * d);
            fill.setColor(Color.argb(35, 0, 180, 255));
            labelBg.setColor(Color.rgb(0, 145, 215));
            labelText.setColor(Color.WHITE);
            labelText.setTextSize(14 * d);
            labelText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float radius = 28 * d;
            float labelHeight = 30 * d;
            float margin = 10 * d;
            float maxLabelWidth = Math.max(80 * d, getWidth() - 2 * margin);
            for (Mark mark : marks) {
                float x = mark.x, y = mark.y;
                RectF shape = new RectF(x - radius, y - radius, x + radius, y + radius);
                if ("box".equals(style)) {
                    canvas.drawRoundRect(shape, 10 * d, 10 * d, fill);
                    canvas.drawRoundRect(shape, 10 * d, 10 * d, ring);
                } else {
                    canvas.drawOval(shape, fill);
                    canvas.drawOval(shape, ring);
                }
                if (mark.label.isEmpty()) continue;
                String label = TextUtils.ellipsize(mark.label, labelText, maxLabelWidth - 20 * d,
                        TextUtils.TruncateAt.END).toString();
                float width = Math.min(maxLabelWidth, labelText.measureText(label) + 20 * d);
                float left = Math.max(margin, Math.min(x - width / 2, getWidth() - margin - width));
                float top = y - radius - labelHeight - 12 * d;
                if (top < margin) top = Math.min(getHeight() - margin - labelHeight, y + radius + 12 * d);
                RectF pill = new RectF(left, top, left + width, top + labelHeight);
                if ("arrow".equals(style)) {
                    float startX = Math.max(pill.left + 12 * d, Math.min(x, pill.right - 12 * d));
                    float startY = top < y ? pill.bottom : pill.top;
                    canvas.drawLine(startX, startY, x, y, ring);
                    double angle = Math.atan2(y - startY, x - startX);
                    float head = 10 * d;
                    canvas.drawLine(x, y, (float) (x - head * Math.cos(angle - 0.55)),
                            (float) (y - head * Math.sin(angle - 0.55)), ring);
                    canvas.drawLine(x, y, (float) (x - head * Math.cos(angle + 0.55)),
                            (float) (y - head * Math.sin(angle + 0.55)), ring);
                }
                canvas.drawRoundRect(pill, labelHeight / 2, labelHeight / 2, labelBg);
                Paint.FontMetrics fm = labelText.getFontMetrics();
                float baseline = pill.centerY() - (fm.ascent + fm.descent) / 2;
                canvas.drawText(label, pill.left + 10 * d, baseline, labelText);
            }
        }
    }
}
