package fr.canape.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Flèche de souris dessinée par-dessus la page, déplacée avec les flèches de la télécommande. */
public class CursorView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private float px = -1f;
    private float py = -1f;

    public CursorView(Context context) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;
        fill.setColor(Color.WHITE);
        fill.setStyle(Paint.Style.FILL);
        stroke.setColor(0xFF111111);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.6f * d);
        stroke.setStrokeJoin(Paint.Join.ROUND);

        float s = 1.7f * d;
        arrow.moveTo(0f, 0f);
        arrow.lineTo(0f, 17f * s);
        arrow.lineTo(4.2f * s, 13.2f * s);
        arrow.lineTo(7.2f * s, 19.8f * s);
        arrow.lineTo(10f * s, 18.6f * s);
        arrow.lineTo(7.1f * s, 12.2f * s);
        arrow.lineTo(12.5f * s, 12.2f * s);
        arrow.close();

        setFocusable(false);
        setClickable(false);
    }

    private void ensurePosition() {
        if (px < 0f && getWidth() > 0) {
            px = getWidth() / 2f;
            py = getHeight() / 2f;
        }
    }

    /** Place le curseur au centre s'il n'a encore jamais été placé. */
    public void placeIfNeeded(float width, float height) {
        if (px < 0f && width > 0f) {
            px = width / 2f;
            py = height / 2f;
            invalidate();
        }
    }

    public float getPointerX() {
        ensurePosition();
        return Math.max(px, 0f);
    }

    public float getPointerY() {
        ensurePosition();
        return Math.max(py, 0f);
    }

    public void setPointer(float x, float y) {
        px = x;
        py = y;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        ensurePosition();
        if (px < 0f) return;
        canvas.save();
        canvas.translate(px, py);
        canvas.drawPath(arrow, fill);
        canvas.drawPath(arrow, stroke);
        canvas.restore();
    }
}
