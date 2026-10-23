package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * A quiet tide curve. Samples are cosine-interpolated so the line reads as water, not as an hourly bar chart.
 */
public class TideCurveView extends View {
    private long[] times = new long[0];
    private float[] heights = new float[0];
    private long now;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final Path water = new Path();

    public TideCurveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2f * d);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(0xFFB7E4F2);
        fill.setStyle(Paint.Style.FILL);
        marker.setStyle(Paint.Style.STROKE);
        marker.setStrokeWidth(1.5f * d);
        marker.setColor(0xCCFFFFFF);
    }

    /** Epoch millis and heights, in order. An empty series clears the curve. */
    public void setSeries(long[] times, float[] heights, long now) {
        this.times = times == null ? new long[0] : times;
        this.heights = heights == null ? new float[0] : heights;
        this.now = now;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (times.length < 2 || heights.length != times.length) return;
        float d = getResources().getDisplayMetrics().density;
        float left = d;
        float right = getWidth() - d;
        float top = 3f * d;
        float bottom = getHeight() - 2f * d;
        if (right <= left || bottom <= top) return;

        long win0 = Math.max(times[0], now - 3 * 3600_000L);
        long win1 = Math.min(times[times.length - 1], Math.max(now + 15 * 3600_000L, win0 + 8 * 3600_000L));
        if (win1 <= win0) return;

        int steps = 48;
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        float[] sample = new float[steps + 1];
        for (int i = 0; i <= steps; i++) {
            long t = win0 + (win1 - win0) * i / steps;
            sample[i] = at(t);
            min = Math.min(min, sample[i]);
            max = Math.max(max, sample[i]);
        }
        float pad = Math.max(0.25f, (max - min) * 0.18f);
        min -= pad;
        max += pad;

        shape.rewind();
        water.rewind();
        for (int i = 0; i <= steps; i++) {
            float x = left + (right - left) * i / steps;
            float y = bottom - (bottom - top) * ((sample[i] - min) / (max - min));
            if (i == 0) {
                shape.moveTo(x, y);
                water.moveTo(x, bottom);
                water.lineTo(x, y);
            } else {
                shape.lineTo(x, y);
                water.lineTo(x, y);
            }
        }
        water.lineTo(right, bottom);
        water.close();
        fill.setShader(new LinearGradient(0, top, 0, bottom, 0x663E8EA8, 0x003E8EA8, Shader.TileMode.CLAMP));
        canvas.drawPath(water, fill);
        canvas.drawPath(shape, line);

        if (now >= win0 && now <= win1) {
            float x = left + (right - left) * (now - win0) / (float) (win1 - win0);
            canvas.drawLine(x, top, x, bottom, marker);
        }
    }

    /** Cosine interpolation between the hourly (or half-hourly) samples, so peaks stay rounded. */
    private float at(long t) {
        if (t <= times[0]) return heights[0];
        if (t >= times[times.length - 1]) return heights[heights.length - 1];
        int i = 1;
        while (i < times.length && times[i] < t) i++;
        long span = times[i] - times[i - 1];
        float u = span <= 0 ? 0f : (t - times[i - 1]) / (float) span;
        float smooth = (1f - (float) Math.cos(u * Math.PI)) / 2f;
        return heights[i - 1] + smooth * (heights[i] - heights[i - 1]);
    }
}
