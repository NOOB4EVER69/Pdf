package com.pdfreader.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_OPEN = 1;
    private static final float MAX_ZOOM = 4f;
    private static final int MAX_BMP_W = 2400;
    private static final String[] MODES = {"Normal", "Night", "Sepia"};
    private static final ColorMatrixColorFilter INVERT = new ColorMatrixColorFilter(new ColorMatrix(new float[]{
            -1, 0, 0, 0, 255,
            0, -1, 0, 0, 255,
            0, 0, -1, 0, 255,
            0, 0, 0, 1, 0}));
    private static final ColorMatrixColorFilter SEPIA = new ColorMatrixColorFilter(new ColorMatrix(new float[]{
            0.393f, 0.769f, 0.189f, 0, 0,
            0.349f, 0.686f, 0.168f, 0, 0,
            0.272f, 0.534f, 0.131f, 0, 0,
            0, 0, 0, 1, 0}));

    private final Object lock = new Object();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 8)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private final List<String[]> recents = new ArrayList<>();

    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private int[] pw = new int[0], ph = new int[0];
    private int pageCount = 0;
    private volatile int gen = 0;

    private File curFile;
    private Uri curUri;
    private String curName = "";

    private float zoom = 1f, pinch = 1f;
    private int mode = 0;
    private boolean keepOn = false, pinching = false, externalLaunch = false, barVisible = true, gdActive = false;

    private SharedPreferences prefs;
    private LinearLayout bar;
    private ListView list, recentList;
    private HorizontalScrollView hsv;
    private View emptyView;
    private TextView title, counter, recentLabel;
    private PageAdapter adapter;
    private RecentAdapter recentAdapter;
    private ScaleGestureDetector scaler;
    private GestureDetector gd;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("pdfreader", MODE_PRIVATE);
        mode = prefs.getInt("mode", 0);
        keepOn = prefs.getBoolean("keep", false);
        applyKeep();
        getWindow().setStatusBarColor(0xFF1F2328);
        buildUi();
        loadRecents();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveProgress();
    }

    @Override
    public void onBackPressed() {
        if (pageCount > 0 && !externalLaunch) closeDoc();
        else super.onBackPressed();
    }

    // ---------- UI ----------

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

    private TextView tv(String t, int size, int color) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF2B2F35);

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF1F2328);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        bar.addView(btn("\uD83D\uDCC2", v -> pickFile()));
        title = tv("", 14, Color.WHITE);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(8), 0, dp(8), 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        counter = tv("", 14, Color.WHITE);
        counter.setGravity(Gravity.CENTER);
        counter.setPadding(dp(10), dp(8), dp(10), dp(8));
        counter.setOnClickListener(v -> goToDialog());
        bar.addView(counter);
        bar.addView(btn("\u22EE", v -> showMenu(v)));
        bar.setVisibility(View.GONE);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout content = new FrameLayout(this);

        hsv = new HorizontalScrollView(this);
        hsv.setFillViewport(true);
        hsv.setHorizontalScrollBarEnabled(false);
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setFastScrollEnabled(true);
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
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.setPadding(0, dp(36), 0, 0);
        TextView h = tv("PDF Reader", 26, Color.WHITE);
        h.setTypeface(null, Typeface.BOLD);
        h.setGravity(Gravity.CENTER);
        empty.addView(h);
        TextView s = tv("Open a PDF, or tap any PDF file on your phone.", 14, 0xB3FFFFFF);
        s.setGravity(Gravity.CENTER);
        s.setPadding(dp(24), dp(8), dp(24), dp(18));
        empty.addView(s);
        Button ob = new Button(this);
        ob.setText("Open PDF");
        ob.setAllCaps(false);
        ob.setTextColor(Color.WHITE);
        ob.setBackgroundColor(0xFF2F81F7);
        ob.setPadding(dp(28), dp(12), dp(28), dp(12));
        ob.setOnClickListener(v -> pickFile());
        empty.addView(ob);
        recentLabel = tv("Recent", 13, 0x99FFFFFF);
        recentLabel.setPadding(dp(16), dp(24), dp(16), dp(4));
        recentLabel.setVisibility(View.GONE);
        empty.addView(recentLabel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        recentList = new ListView(this);
        recentList.setDivider(null);
        recentAdapter = new RecentAdapter();
        recentList.setAdapter(recentAdapter);
        recentList.setOnItemClickListener((p, v, i, id) -> {
            externalLaunch = false;
            openUri(Uri.parse(recents.get(i)[0]));
        });
        recentList.setOnItemLongClickListener((p, v, i, id) -> {
            removeRecent(recents.get(i)[0]);
            toast("Removed from recent");
            return true;
        });
        empty.addView(recentList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
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

        gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                barVisible = !barVisible;
                bar.setVisibility(barVisible ? View.VISIBLE : View.GONE);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                applyZoom(zoom > 1.05f ? 1f : 2.5f);
                return true;
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (pageCount > 0) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                gdActive = !(barVisible && ev.getY() < bar.getBottom());
            }
            if (gdActive) gd.onTouchEvent(ev);
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

    private void applyKeep() {
        if (keepOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void toast(String m) {
        ui.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
    }

    // ---------- Menu & actions ----------

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Go to page");
        m.getMenu().add(0, 2, 1, "Reading mode: " + MODES[mode]);
        m.getMenu().add(0, 3, 2, "Keep screen on: " + (keepOn ? "ON" : "OFF"));
        m.getMenu().add(0, 4, 3, "Share");
        m.getMenu().add(0, 5, 4, "Print");
        m.getMenu().add(0, 6, 5, "Document info");
        m.getMenu().add(0, 7, 6, "Close document");
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: goToDialog(); break;
                case 2:
                    mode = (mode + 1) % 3;
                    prefs.edit().putInt("mode", mode).apply();
                    adapter.notifyDataSetChanged();
                    break;
                case 3:
                    keepOn = !keepOn;
                    prefs.edit().putBoolean("keep", keepOn).apply();
                    applyKeep();
                    break;
                case 4: share(); break;
                case 5: print(); break;
                case 6: info(); break;
                case 7: closeDoc(); break;
            }
            return true;
        });
        m.show();
    }

    private void goTo(int i) {
        if (pageCount == 0) return;
        list.setSelection(Math.max(0, Math.min(pageCount - 1, i)));
    }

    private void goToDialog() {
        if (pageCount == 0) return;
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint("1 - " + pageCount);
        new AlertDialog.Builder(this).setTitle("Go to page").setView(e)
                .setPositiveButton("Go", (d, w) -> {
                    try { goTo(Integer.parseInt(e.getText().toString().trim()) - 1); } catch (Exception ignored) { }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void share() {
        if (curUri == null) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_STREAM, curUri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Share PDF"));
    }

    private void print() {
        final File file = curFile;
        final String nm = curName;
        final int pc = pageCount;
        if (file == null) return;
        PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
        pm.print(nm, new PrintDocumentAdapter() {
            @Override
            public void onLayout(PrintAttributes o, PrintAttributes n, CancellationSignal cs, LayoutResultCallback cb, Bundle x) {
                if (cs.isCanceled()) { cb.onLayoutCancelled(); return; }
                cb.onLayoutFinished(new PrintDocumentInfo.Builder(nm + ".pdf")
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(pc).build(), true);
            }
            @Override
            public void onWrite(PageRange[] pages, ParcelFileDescriptor dest, CancellationSignal cs, WriteResultCallback cb) {
                try (InputStream in = new FileInputStream(file);
                     OutputStream out = new FileOutputStream(dest.getFileDescriptor())) {
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    cb.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                } catch (Throwable e) {
                    cb.onWriteFailed(e.toString());
                }
            }
        }, null);
    }

    private void info() {
        if (pageCount == 0 || curFile == null) return;
        long kb = curFile.length() / 1024;
        String size = kb >= 1024 ? String.format(Locale.US, "%.1f MB", kb / 1024f) : kb + " KB";
        String msg = curName + "\n\nPages: " + pageCount + "\nSize: " + size
                + "\nPage size: " + Math.round(pw[0] / 72f * 25.4f) + " \u00D7 " + Math.round(ph[0] / 72f * 25.4f) + " mm";
        new AlertDialog.Builder(this).setTitle("Document info").setMessage(msg).setPositiveButton("OK", null).show();
    }

    // ---------- Recents ----------

    private void loadRecents() {
        recents.clear();
        for (String line : prefs.getString("recents", "").split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            if (f.length >= 3) recents.add(f);
        }
        refreshRecents();
    }

    private void saveRecents() {
        StringBuilder sb = new StringBuilder();
        for (String[] r : recents) sb.append(r[0]).append('\t').append(r[1]).append('\t').append(r[2]).append('\n');
        prefs.edit().putString("recents", sb.toString()).apply();
        refreshRecents();
    }

    private void refreshRecents() {
        recentLabel.setVisibility(recents.isEmpty() ? View.GONE : View.VISIBLE);
        recentAdapter.notifyDataSetChanged();
    }

    private int pageOf(String[] r) {
        try { return Integer.parseInt(r[2]); } catch (Exception e) { return 0; }
    }

    private int savedPage(String uri) {
        for (String[] r : recents) if (r[0].equals(uri)) return pageOf(r);
        return 0;
    }

    private void touchRecent(String uri, String name, int page) {
        for (int i = 0; i < recents.size(); i++) {
            if (recents.get(i)[0].equals(uri)) { recents.remove(i); break; }
        }
        recents.add(0, new String[]{uri, name.replaceAll("[\\t\\r\\n]", " "), String.valueOf(page)});
        while (recents.size() > 25) recents.remove(recents.size() - 1);
        saveRecents();
    }

    private void removeRecent(String uri) {
        for (int i = 0; i < recents.size(); i++) {
            if (recents.get(i)[0].equals(uri)) { recents.remove(i); break; }
        }
        saveRecents();
    }

    private void saveProgress() {
        if (curUri != null && pageCount > 0) touchRecent(curUri.toString(), curName, list.getFirstVisiblePosition());
    }

    private class RecentAdapter extends BaseAdapter {
        @Override public int getCount() { return recents.size(); }
        @Override public Object getItem(int i) { return recents.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override
        public View getView(int i, View cv, ViewGroup parent) {
            LinearLayout row;
            TextView a, b;
            if (cv == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(16), dp(10), dp(16), dp(10));
                a = tv("", 16, Color.WHITE);
                a.setSingleLine(true);
                a.setEllipsize(android.text.TextUtils.TruncateAt.END);
                b = tv("", 12, 0x99FFFFFF);
                row.addView(a);
                row.addView(b);
                row.setTag(new TextView[]{a, b});
            } else {
                row = (LinearLayout) cv;
                TextView[] t = (TextView[]) row.getTag();
                a = t[0];
                b = t[1];
            }
            String[] r = recents.get(i);
            a.setText(r[1]);
            b.setText("Page " + (pageOf(r) + 1));
            return row;
        }
    }

    // ---------- Opening files ----------

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i, REQ_OPEN);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OPEN && res == RESULT_OK && data != null && data.getData() != null) {
            externalLaunch = false;
            persist(data.getData());
            openUri(data.getData());
        }
    }

    private void persist(Uri u) {
        try {
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) { }
    }

    @SuppressWarnings("deprecation")
    private void handleIntent(Intent i) {
        if (i == null) return;
        Uri u = null;
        if (Intent.ACTION_VIEW.equals(i.getAction())) u = i.getData();
        else if (Intent.ACTION_SEND.equals(i.getAction())) u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u != null) {
            externalLaunch = true;
            persist(u);
            openUri(u);
        }
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Throwable ignored) { }
        String s = u.getLastPathSegment();
        return s == null ? "document.pdf" : s;
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
                    saveProgress();
                    synchronized (lock) {
                        gen++;
                        closeCurrent();
                        renderer = r;
                        pfd = p;
                        pw = w;
                        ph = h;
                        pageCount = n;
                    }
                    cache.evictAll();
                    for (File old : getCacheDir().listFiles()) {
                        if (old.getName().startsWith("current_") && !old.equals(f)) old.delete();
                    }
                    int page = Math.min(savedPage(uri.toString()), n - 1);
                    curUri = uri;
                    curFile = f;
                    curName = name;
                    touchRecent(uri.toString(), name, page);
                    title.setText(name);
                    barVisible = true;
                    bar.setVisibility(View.VISIBLE);
                    emptyView.setVisibility(View.GONE);
                    hsv.setVisibility(View.VISIBLE);
                    zoom = 1f;
                    list.setLayoutParams(new FrameLayout.LayoutParams(contentW(), ViewGroup.LayoutParams.MATCH_PARENT));
                    adapter.notifyDataSetChanged();
                    list.setSelection(page);
                    counter.setText((page + 1) + " / " + n);
                });
            } catch (Throwable e) {
                ui.post(() -> removeRecent(uri.toString()));
                toast("Could not open this PDF");
            }
        });
    }

    private void closeDoc() {
        saveProgress();
        synchronized (lock) {
            gen++;
            closeCurrent();
            pageCount = 0;
            pw = new int[0];
            ph = new int[0];
        }
        cache.evictAll();
        curUri = null;
        curFile = null;
        adapter.notifyDataSetChanged();
        hsv.setVisibility(View.GONE);
        bar.setVisibility(View.GONE);
        emptyView.setVisibility(View.VISIBLE);
        title.setText("");
        counter.setText("");
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

    // ---------- Pages ----------

    private ColorMatrixColorFilter filter() {
        return mode == 1 ? INVERT : mode == 2 ? SEPIA : null;
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
            iv.setColorFilter(filter());
            iv.setTag(pos);
            render(iv, pos, cw, ph[pos], pw[pos]);
            return iv;
        }
    }

    private void render(ImageView iv, int pos, int cw, int pageH, int pageW) {
        final int g = gen;
        final int bw = Math.min(cw, MAX_BMP_W);
        final int bh = Math.round(bw * (float) pageH / pageW);
        final String key = g + ":" + pos + ":" + bw;
        Bitmap hit = cache.get(key);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
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
            cache.put(key, bmp);
            ui.post(() -> {
                if (g == gen && Integer.valueOf(pos).equals(iv.getTag())) iv.setImageBitmap(bmp);
            });
        });
    }
}
