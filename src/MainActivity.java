package com.pdfreader.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_OPEN = 1;
    private static final float MAX_ZOOM = 3f;
    private static final int MAX_BMP_W = 2400;
    private static final ColorMatrixColorFilter INVERT = new ColorMatrixColorFilter(new ColorMatrix(new float[]{
            -1, 0, 0, 0, 255,
            0, -1, 0, 0, 255,
            0, 0, -1, 0, 255,
            0, 0, 0, 1, 0}));

    private final Object lock = new Object();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private int[] pw = new int[0], ph = new int[0];
    private int pageCount = 0;
    private volatile int gen = 0;

    private float zoom = 1f, pinch = 1f;
    private boolean night = false, pinching = false;

    private ListView list;
    private HorizontalScrollView hsv;
    private View emptyView;
    private TextView title, counter;
    private PageAdapter adapter;
    private ScaleGestureDetector scaler;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF1F2328);
        buildUi();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private Button btn(String t, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(t);
        x.setAllCaps(false);
        x.setTextColor(Color.WHITE);
        x.setBackgroundColor(0xFF3A4048);
        x.setMinWidth(0);
        x.setMinimumWidth(dp(44));
        x.setPadding(dp(10), 0, dp(10), 0);
        x.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        lp.leftMargin = dp(4);
        x.setLayoutParams(lp);
        return x;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF2B2F35);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF1F2328);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));

        bar.addView(btn("Open", v -> pickFile()));
        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(8), 0, dp(8), 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(btn("\u2212", v -> applyZoom(zoom / 1.25f)));
        bar.addView(btn("+", v -> applyZoom(zoom * 1.25f)));
        counter = new TextView(this);
        counter.setTextColor(Color.WHITE);
        counter.setTextSize(13);
        counter.setGravity(Gravity.CENTER);
        counter.setMinWidth(dp(56));
        bar.addView(counter);
        bar.addView(btn("\uD83C\uDF19", v -> {
            night = !night;
            adapter.notifyDataSetChanged();
        }));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout content = new FrameLayout(this);
        hsv = new HorizontalScrollView(this);
        hsv.setFillViewport(true);
        hsv.setHorizontalScrollBarEnabled(false);
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setVerticalScrollBarEnabled(false);
        adapter = new PageAdapter();
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) { }
            @Override public void onScroll(AbsListView v, int first, int visible, int total) {
                if (total > 0) counter.setText((first + 1) + " / " + total);
            }
        });
        hsv.addView(list, new FrameLayout.LayoutParams(contentW(), ViewGroup.LayoutParams.MATCH_PARENT));
        hsv.setVisibility(View.GONE);
        content.addView(hsv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        TextView h = new TextView(this);
        h.setText("PDF Reader");
        h.setTextColor(Color.WHITE);
        h.setTextSize(26);
        h.setTypeface(null, Typeface.BOLD);
        h.setGravity(Gravity.CENTER);
        empty.addView(h);
        TextView s = new TextView(this);
        s.setText("Open a PDF, or tap any PDF file on your phone.");
        s.setTextColor(0xB3FFFFFF);
        s.setGravity(Gravity.CENTER);
        s.setPadding(dp(24), dp(8), dp(24), dp(20));
        empty.addView(s);
        Button ob = new Button(this);
        ob.setText("Open PDF");
        ob.setAllCaps(false);
        ob.setTextColor(Color.WHITE);
        ob.setBackgroundColor(0xFF2F81F7);
        ob.setPadding(dp(28), dp(12), dp(28), dp(12));
        ob.setOnClickListener(v -> pickFile());
        empty.addView(ob);
        emptyView = empty;
        content.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        scaler = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { pinch = 1f; return true; }
            @Override public boolean onScale(ScaleGestureDetector d) {
                float p = pinch * d.getScaleFactor();
                p = Math.max(1f / zoom, Math.min(MAX_ZOOM / zoom, p));
                pinch = p;
                list.setPivotX(d.getFocusX() + hsv.getScrollX());
                list.setPivotY(d.getFocusY());
                list.setScaleX(p);
                list.setScaleY(p);
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d) {
                float p = pinch;
                list.setScaleX(1f);
                list.setScaleY(1f);
                pinch = 1f;
                applyZoom(zoom * p);
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (pageCount > 0) {
            scaler.onTouchEvent(ev);
            if (scaler.isInProgress()) {
                if (!pinching) {
                    pinching = true;
                    MotionEvent c = MotionEvent.obtain(ev);
                    c.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(c);
                    c.recycle();
                }
                return true;
            }
            if (pinching) {
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) pinching = false;
                return true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        ui.post(() -> applyZoom(zoom));
    }

    private int contentW() {
        return Math.round(getResources().getDisplayMetrics().widthPixels * zoom);
    }

    private void applyZoom(float z) {
        zoom = Math.max(1f, Math.min(MAX_ZOOM, z));
        int first = list.getFirstVisiblePosition();
        list.setLayoutParams(new FrameLayout.LayoutParams(contentW(), ViewGroup.LayoutParams.MATCH_PARENT));
        adapter.notifyDataSetChanged();
        list.setSelection(first);
        if (zoom == 1f) hsv.scrollTo(0, 0);
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        startActivityForResult(i, REQ_OPEN);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OPEN && res == RESULT_OK && data != null && data.getData() != null) openUri(data.getData());
    }

    @SuppressWarnings("deprecation")
    private void handleIntent(Intent i) {
        if (i == null) return;
        Uri u = null;
        if (Intent.ACTION_VIEW.equals(i.getAction())) u = i.getData();
        else if (Intent.ACTION_SEND.equals(i.getAction())) u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u != null) openUri(u);
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Throwable ignored) { }
        String s = u.getLastPathSegment();
        return s == null ? "document.pdf" : s;
    }

    private void toast(String m) {
        ui.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
    }

    private void openUri(Uri uri) {
        io.execute(() -> {
            try {
                File f = new File(getCacheDir(), "current_" + System.nanoTime() + ".pdf");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                ParcelFileDescriptor p = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                PdfRenderer r;
                try {
                    r = new PdfRenderer(p);
                } catch (SecurityException e) {
                    p.close();
                    f.delete();
                    toast("Password-protected PDFs aren't supported yet");
                    return;
                }
                int n = r.getPageCount();
                int[] w = new int[n], h = new int[n];
                for (int i = 0; i < n; i++) {
                    try (PdfRenderer.Page pg = r.openPage(i)) {
                        w[i] = Math.max(1, pg.getWidth());
                        h[i] = Math.max(1, pg.getHeight());
                    }
                }
                String name = displayName(uri).replaceAll("(?i)\\.pdf$", "");
                ui.post(() -> {
                    synchronized (lock) {
                        gen++;
                        closeCurrent();
                        renderer = r;
                        pfd = p;
                        pw = w;
                        ph = h;
                        pageCount = n;
                    }
                    for (File old : getCacheDir().listFiles()) {
                        if (old.getName().startsWith("current_") && !old.equals(f)) old.delete();
                    }
                    title.setText(name);
                    emptyView.setVisibility(View.GONE);
                    hsv.setVisibility(View.VISIBLE);
                    zoom = 1f;
                    list.setLayoutParams(new FrameLayout.LayoutParams(contentW(), ViewGroup.LayoutParams.MATCH_PARENT));
                    adapter.notifyDataSetChanged();
                    list.setSelection(0);
                    counter.setText("1 / " + n);
                });
            } catch (Throwable e) {
                toast("Could not open this PDF");
            }
        });
    }

    private void closeCurrent() {
        try { if (renderer != null) renderer.close(); } catch (Throwable ignored) { }
        try { if (pfd != null) pfd.close(); } catch (Throwable ignored) { }
        renderer = null;
        pfd = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        synchronized (lock) { gen++; closeCurrent(); }
        io.shutdownNow();
    }

    private class PageAdapter extends BaseAdapter {
        @Override public int getCount() { return pageCount; }
        @Override public Object getItem(int i) { return null; }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            ImageView iv = cv instanceof ImageView ? (ImageView) cv : new ImageView(MainActivity.this);
            int gap = dp(10);
            int cw = contentW();
            int h = Math.round(cw * (float) ph[pos] / pw[pos]);
            iv.setScaleType(ImageView.ScaleType.FIT_START);
            iv.setLayoutParams(new AbsListView.LayoutParams(cw, h + gap));
            iv.setPadding(0, 0, 0, gap);
            iv.setImageBitmap(null);
            iv.setColorFilter(night ? INVERT : null);
            iv.setTag(pos);
            render(iv, pos, cw, ph[pos], pw[pos]);
            return iv;
        }
    }

    private void render(ImageView iv, int pos, int cw, int pageH, int pageW) {
        final int g = gen;
        final int bw = Math.min(cw, MAX_BMP_W);
        final int bh = Math.round(bw * (float) pageH / pageW);
        io.execute(() -> {
            if (g != gen || !Integer.valueOf(pos).equals(iv.getTag())) return;
            Bitmap bmp;
            synchronized (lock) {
                if (renderer == null || g != gen) return;
                try (PdfRenderer.Page p = renderer.openPage(pos)) {
                    bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                } catch (Throwable e) {
                    return;
                }
            }
            ui.post(() -> {
                if (g == gen && Integer.valueOf(pos).equals(iv.getTag())) iv.setImageBitmap(bmp);
            });
        });
    }
}
