package app.suiyi.translate;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small native UI primitives shared by the conversation and settings screens. */
final class Ui {
    static final int INK = Color.rgb(24, 28, 43), MUTED = Color.rgb(125, 132, 152);
    static final int ACCENT = Color.rgb(102, 83, 235), SOFT = Color.rgb(239, 236, 255);
    static final int BG = Color.rgb(247, 248, 252), LINE = Color.rgb(230, 232, 242);
    static int dp(Context c, float n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    static LinearLayout column(Context c) { LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); return v; }
    static LinearLayout row(Context c) { LinearLayout v = new LinearLayout(c); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    static TextView text(Context c, String s, float size, int color, boolean bold) {
        TextView v = new TextView(c); v.setText(s); v.setTextSize(size); v.setTextColor(color);
        v.setFontFeatureSettings("kern"); v.setIncludeFontPadding(false);
        v.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return v;
    }
    static GradientDrawable shape(Context c, int color, float radius, boolean stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c, radius));
        if (stroke) d.setStroke(dp(c, 1), LINE); return d;
    }
    static RippleDrawable ripple(Context c, int color, float radius, boolean stroke) {
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(28, 102, 83, 235)), shape(c, color, radius, stroke), shape(c, Color.WHITE, radius, false));
    }
    static Button button(Context c, String s, boolean primary, View.OnClickListener action) {
        Button v = new Button(c); v.setText(s); v.setTextSize(15); v.setAllCaps(false);
        v.setSingleLine(true); v.setLetterSpacing(0); v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setTextColor(primary ? Color.WHITE : ACCENT); v.setMinHeight(0); v.setMinimumHeight(0);
        v.setMinWidth(0); v.setMinimumWidth(0); v.setStateListAnimator(null); v.setElevation(0);
        v.setPadding(dp(c, 18), 0, dp(c, 18), 0); v.setBackground(ripple(c, primary ? ACCENT : SOFT, 16, false));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(c, 50)); p.setMargins(0, dp(c, 6), 0, dp(c, 6)); v.setLayoutParams(p);
        if (action != null) v.setOnClickListener(action); return v;
    }
    static LinearLayout card(Context c, int color) {
        LinearLayout v = column(c); v.setPadding(dp(c, 20), dp(c, 20), dp(c, 20), dp(c, 18));
        v.setBackground(shape(c, color, 24, color == Color.WHITE));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 0, 0, dp(c, 14)); v.setLayoutParams(p); return v;
    }
    static void gap(LinearLayout parent, int size) { View v = new View(parent.getContext()); parent.addView(v, new LinearLayout.LayoutParams(1, dp(parent.getContext(), size))); }
    static View line(Context c) { View v = new View(c); v.setBackgroundColor(LINE); v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(c, 1))); return v; }
    static LinearLayout iconButton(Context c, String icon, String label, int color, boolean vertical, View.OnClickListener action) {
        LinearLayout v = vertical ? column(c) : row(c); v.setGravity(Gravity.CENTER);
        v.setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8));
        v.setBackground(ripple(c, Color.TRANSPARENT, 14, false));
        Glyph glyph = new Glyph(c, icon, color); v.addView(glyph, new LinearLayout.LayoutParams(dp(c, 21), dp(c, 21)));
        if (!label.isEmpty()) {
            TextView name = text(c, label, vertical ? 11 : 14, color, false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
            if (vertical) p.topMargin = dp(c, 7); else p.leftMargin = dp(c, 7);
            v.addView(name, p);
        }
        v.setContentDescription(label); v.setFocusable(true); if (action != null) v.setOnClickListener(action); return v;
    }
    static Button action(Context c, String icon, String label, View.OnClickListener listener) {
        Button v = button(c, label, false, listener); v.setTextSize(11); v.setTextColor(MUTED);
        v.setBackground(ripple(c, Color.TRANSPARENT, 12, false)); v.setPadding(0, dp(c, 8), 0, dp(c, 8));
        int n = dp(c, 20); Bitmap bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Glyph glyph = new Glyph(c, icon, ACCENT); glyph.layout(0, 0, n, n); glyph.draw(new Canvas(bitmap));
        v.setCompoundDrawablesWithIntrinsicBounds(null, new BitmapDrawable(c.getResources(), bitmap), null, null);
        v.setCompoundDrawablePadding(dp(c, 6)); v.setContentDescription(label); return v;
    }
    static Dialog page(Context c, LinearLayout root) {
        Dialog d = new Dialog(c, R.style.AppTheme); d.requestWindowFeature(Window.FEATURE_NO_TITLE); d.setContentView(root);
        Window w = d.getWindow(); if (w != null) {
            w.setBackgroundDrawable(shape(c, BG, 0, false)); w.setLayout(-1, -1);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        return d;
    }
    static final class Glyph extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String name; private int color;
        Glyph(Context c, String name, int color) { super(c); this.name = name; this.color = color; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void color(int value) { color = value; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); c.save(); c.scale(getWidth()/24f, getHeight()/24f);
            p.setColor(color); p.setStrokeWidth(1.65f); p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            switch (name) {
                case "mic": c.drawRoundRect(new RectF(8, 2, 16, 15), 4, 4, p); path(c,5,10,5,12,6,17,12,19,18,17,19,12,19,10); c.drawLine(12,19,12,22,p); c.drawLine(8,22,16,22,p); break;
                case "swap": path(c,3,7,20,7,16,3); path(c,21,17,4,17,8,21); break;
                case "chevron": path(c,9,5,16,12,9,19); break;
                case "down": path(c,6,9,12,15,18,9); break;
                case "back": path(c,14,5,7,12,14,19); break;
                case "arrow": path(c,5,12,19,12,13,6); path(c,19,12,13,18); break;
                case "close": c.drawLine(6,6,18,18,p); c.drawLine(18,6,6,18,p); break;
                case "history": c.drawArc(new RectF(3,3,21,21), -135, 315, false, p); path(c,3,3,3,8,8,8); path(c,12,7,12,12,15,14); break;
                case "settings": c.drawCircle(12,12,3,p); for(int i=0;i<8;i++){double a=i*Math.PI/4;c.drawLine(12+(float)Math.cos(a)*8,12+(float)Math.sin(a)*8,12+(float)Math.cos(a)*10,12+(float)Math.sin(a)*10,p);} c.drawCircle(12,12,8,p); break;
                case "keyboard": c.drawRoundRect(new RectF(2,5,22,19),3,3,p); for(int y=9;y<=12;y+=3)for(int x=6;x<=18;x+=4)c.drawPoint(x,y,p); c.drawLine(7,16,17,16,p); break;
                case "volume": path(c,3,9,7,9,12,5,12,19,7,15,3,15,3,9); c.drawArc(new RectF(10,7,19,17),-60,120,false,p); c.drawArc(new RectF(9,3,24,21),-55,110,false,p); break;
                case "copy": c.drawRoundRect(new RectF(8,8,21,21),3,3,p); path(c,16,5,16,3,4,3,3,4,3,16,5,16); break;
                case "heart": { Path h=new Path();h.moveTo(12,21);h.cubicTo(9,18,2,13,2,8);h.cubicTo(2,1,10,1,12,7);h.cubicTo(14,1,22,1,22,8);h.cubicTo(22,13,15,18,12,21);c.drawPath(h,p);break; }
                case "expand": path(c,8,3,3,3,3,8);path(c,16,3,21,3,21,8);path(c,3,16,3,21,8,21);path(c,21,16,21,21,16,21);break;
                case "new": c.drawCircle(12,12,9,p);c.drawLine(12,7,12,17,p);c.drawLine(7,12,17,12,p);break;
                case "key": c.drawCircle(8,8,5,p);path(c,12,12,21,21,21,17,17,17,17,14);break;
                case "check": path(c,5,12,10,17,20,6);break;
                case "globe": c.drawCircle(12,12,9,p);c.drawOval(new RectF(8,3,16,21),p);c.drawLine(3,12,21,12,p);break;
                case "logo": path(c,2,6,13,6);path(c,8,3,8,6,10,11,4,16);path(c,4,8,11,15);path(c,12,21,17,9,22,21);c.drawLine(14,17,20,17,p);break;
                default: c.drawCircle(12,12,8,p);
            }
            c.restore();
        }
        private void path(Canvas c, float... points) { Path path=new Path();path.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);c.drawPath(path,p); }
    }
}
