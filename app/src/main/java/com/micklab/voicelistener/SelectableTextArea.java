package com.micklab.voicelistener;

import android.content.Context;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

public class SelectableTextArea extends EditText {
    private boolean guardParentScrollInterception = false;

    public SelectableTextArea(Context context) {
        super(context);
        initialize();
    }

    public SelectableTextArea(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public SelectableTextArea(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize();
    }

    private void initialize() {
        setGravity(Gravity.TOP | Gravity.START);
        setLongClickable(true);
        setTextIsSelectable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setHorizontallyScrolling(false);
        setVerticalScrollBarEnabled(true);
        setMovementMethod(ScrollingMovementMethod.getInstance());
        setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
    }

    public void configureEditableText() {
        setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        setCursorVisible(true);
        setShowSoftInputOnFocus(true);
    }

    public void configureReadOnlyText() {
        setKeyListener(null);
        setCursorVisible(false);
        setShowSoftInputOnFocus(false);
    }

    public void setFixedHeight(int heightPx) {
        setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            heightPx
        ));
    }

    public void setWeightedHeight(float weight) {
        setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            weight
        ));
    }

    public void enableParentScrollGuard() {
        guardParentScrollInterception = true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (guardParentScrollInterception && getParent() != null && event != null) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    break;
                default:
                    break;
            }
        }
        return super.onTouchEvent(event);
    }
}
