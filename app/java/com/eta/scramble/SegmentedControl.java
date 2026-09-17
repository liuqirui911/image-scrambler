package com.eta.scramble;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * MIUI 风格的胶囊分段控件：灰色轨道 + 白色胶囊滑块 + 平滑过渡动画，纯自绘，无第三方依赖。
 */
public class SegmentedControl extends View {

    public interface OnSelectListener {
        void onSelect(int index);
    }

    private String[] items = new String[0];
    private int selected = 0;
    private float progress = 0f;
    private int pressedIndex = -1;
    private OnSelectListener listener;
    private ValueAnimator animator;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();

    public SegmentedControl(Context context) {
        super(context);
        init();
    }

    public SegmentedControl(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SegmentedControl(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        trackPaint.setColor(getContext().getColor(R.color.field_bg));
        pillPaint.setColor(getContext().getColor(R.color.card_bg));
        pillPaint.setShadowLayer(dp(3f), 0f, dp(1f), 0x22000000);
        pressPaint.setColor(getContext().getColor(R.color.ripple_light));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(sp(13.5f));
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setClickable(true);
        setFocusable(true);
        updateContentDescription();
    }

    public void setItems(String[] newItems) {
        items = newItems == null ? new String[0] : newItems;
        if (selected >= items.length) selected = Math.max(0, items.length - 1);
        progress = selected;
        invalidate();
        updateContentDescription();
    }

    public int getSelectedIndex() {
        return selected;
    }

    public void setSelectedIndex(int index) {
        if (items.length == 0) return;
        int target = index < 0 ? 0 : (index >= items.length ? items.length - 1 : index);
        if (target == selected) {
            animateTo(target);
            return;
        }
        selected = target;
        animateTo(target);
        if (listener != null) listener.onSelect(target);
        updateContentDescription();
    }

    public void setOnSelectListener(OnSelectListener l) {
        listener = l;
    }

    private void animateTo(float target) {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(180L);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                progress = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = resolveSize((int) dp(40f), heightMeasureSpec);
        int width = resolveSize((int) dp(168f), widthMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float radius = h / 2f;
        rf.set(0f, 0f, w, h);
        canvas.drawRoundRect(rf, radius, radius, trackPaint);
        if (items.length == 0) return;

        float seg = w / (float) items.length;
        float left = progress * seg;
        rf.set(left + dp(2f), dp(2f), left + seg - dp(2f), h - dp(2f));
        float pillRadius = (h - dp(4f)) / 2f;
        canvas.drawRoundRect(rf, pillRadius, pillRadius, pillPaint);

        if (pressedIndex >= 0 && pressedIndex < items.length) {
            rf.set(pressedIndex * seg + dp(2f), dp(2f), (pressedIndex + 1) * seg - dp(2f), h - dp(2f));
            canvas.drawRoundRect(rf, pillRadius, pillRadius, pressPaint);
        }

        int primary = getContext().getColor(R.color.text_primary);
        int secondary = getContext().getColor(R.color.text_secondary);
        for (int i = 0; i < items.length; i++) {
            boolean isSelected = i == selected;
            textPaint.setFakeBoldText(isSelected);
            textPaint.setColor(isSelected ? primary : secondary);
            float cx = seg * i + seg / 2f;
            float cy = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText(items[i], cx, cy, textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressedIndex = indexAt(event.getX());
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                int moved = indexAt(event.getX());
                if (moved != pressedIndex) {
                    pressedIndex = moved;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                int up = indexAt(event.getX());
                pressedIndex = -1;
                if (up >= 0) setSelectedIndex(up);
                invalidate();
                // 注意：这里不能调用 performClick()，否则会再触发一次“切到下一项”，
                // 结果就是点哪一项都会跳到另一项。真实触摸只需按点中的格子设置选中项。
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressedIndex = -1;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /**
     * 读屏 / 自动化以 ACTION_CLICK 触发时（没有具体坐标），依次切到下一个选项；
     * 真实触摸走 onTouchEvent，不会经过这里。
     */
    @Override
    public boolean performClick() {
        if (items.length > 1) {
            setSelectedIndex((selected + 1) % items.length);
        }
        return super.performClick();
    }

    /** 给读屏工具一句可读的状态描述。 */
    private void updateContentDescription() {
        if (items.length == 0) {
            setContentDescription(null);
            return;
        }
        StringBuilder sb = new StringBuilder(getClass().getSimpleName().isEmpty() ? "" : "");
        sb.append('[');
        sb.append(join(items));
        sb.append("] 当前：");
        sb.append(items[selected]);
        setContentDescription(sb.toString());
    }

    private static String join(String[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private int indexAt(float x) {
        if (items.length == 0 || getWidth() == 0) return -1;
        float seg = getWidth() / (float) items.length;
        int index = (int) (x / seg);
        if (index < 0) index = 0;
        if (index >= items.length) index = items.length - 1;
        return index;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
