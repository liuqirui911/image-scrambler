package com.eta.scramble;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.provider.OpenableColumns;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 主界面：混淆 / 还原。界面为 MIUI 风格，全部图片处理都在本机完成。 */
public class MainActivity extends Activity {

    private static final String TAG = "混淆图";
    private static final int REQ_PICK = 1001;
    private static final int REQ_PERM = 1002;
    /** 只读取文件开头这些字节：足够解析 PNG 标记与 EXIF，避免把整张大图读进内存。 */
    private static final int HEAD_BYTES = 256 * 1024;
    /** 混淆时允许的像素上限（超过等比缩小）；还原时再按堆内存放宽。 */
    private static final int MAX_PIXELS = 8000000;
    private static final int MAX_EDGE = 4096;
    private static final int RESTORE_MAX_EDGE = 8192;
    private static final int RESTORE_MAX_PIXELS = 12000000;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private SegmentedControl modeSeg;
    private SegmentedControl strengthSeg;
    private ImageView srcImage;
    private ImageView dstImage;
    private LinearLayout emptyHint;
    private TextView emptyText;
    private TextView srcInfo;
    private TextView tipText;
    private TextView btnRun;
    private TextView btnRepick;
    private TextView btnSave;
    private TextView btnShare;
    private TextView btnAbout;
    private TextView btnKeyToggle;
    private TextView resultInfo;
    private LinearLayout resultCard;
    private EditText keyInput;
    private CheckBox tagSwitch;
    private CheckBox autoSwitch;
    private FrameLayout pickCard;
    private TextView formatValue;
    private TextView keyDesc;
    private LinearLayout autoRow;

    private int format = PopularCodecs.FORMAT_TOMATO;
    private int tagFormat = -1;
    private int cropWidth;
    private int cropHeight;

    private Bitmap srcBitmap;
    private Bitmap resultBitmap;
    private byte[] resultBytes;
    private Uri savedUri;
    private String pendingName;
    private boolean busy;
    private boolean keyVisible;
    private boolean pendingShare;

    private static final class Decoded {
        Bitmap bitmap;
        int srcWidth;
        int srcHeight;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        modeSeg = findViewById(R.id.modeSeg);
        strengthSeg = findViewById(R.id.strengthSeg);
        srcImage = findViewById(R.id.srcImage);
        dstImage = findViewById(R.id.dstImage);
        emptyHint = findViewById(R.id.emptyHint);
        emptyText = findViewById(R.id.emptyText);
        srcInfo = findViewById(R.id.srcInfo);
        tipText = findViewById(R.id.tipText);
        btnRun = findViewById(R.id.btnRun);
        btnRepick = findViewById(R.id.btnRepick);
        btnSave = findViewById(R.id.btnSave);
        btnShare = findViewById(R.id.btnShare);
        btnAbout = findViewById(R.id.btnAbout);
        btnKeyToggle = findViewById(R.id.keyToggle);
        resultInfo = findViewById(R.id.resultInfo);
        resultCard = findViewById(R.id.resultCard);
        keyInput = findViewById(R.id.keyInput);
        tagSwitch = findViewById(R.id.tagSwitch);
        pickCard = findViewById(R.id.pickCard);
        formatValue = findViewById(R.id.formatValue);
        keyDesc = findViewById(R.id.keyDesc);
        autoRow = findViewById(R.id.autoRow);
        autoSwitch = findViewById(R.id.autoSwitch);

        findViewById(R.id.formatRow).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFormatDialog();
            }
        });

        modeSeg.setItems(new String[] {getString(R.string.tab_scramble), getString(R.string.tab_restore)});
        strengthSeg.setItems(new String[] {getString(R.string.strength_1), getString(R.string.strength_2),
                getString(R.string.strength_3)});
        strengthSeg.setSelectedIndex(1);
        modeSeg.setSelectedIndex(0);
        modeSeg.setOnSelectListener(new SegmentedControl.OnSelectListener() {
            @Override
            public void onSelect(int index) {
                updateMode();
            }
        });

        View.OnClickListener pick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage();
            }
        };
        pickCard.setOnClickListener(pick);
        btnRepick.setOnClickListener(pick);

        btnRun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startTask();
            }
        });
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveResult(false);
            }
        });
        btnShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveResult(true);
            }
        });
        btnAbout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AboutActivity.class));
            }
        });
        btnKeyToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleKeyVisible();
            }
        });

        updateMode();
        updateKeyToggle();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (srcBitmap != null) {
            srcBitmap.recycle();
            srcBitmap = null;
        }
        if (resultBitmap != null) {
            resultBitmap.recycle();
            resultBitmap = null;
        }
    }

    private void updateMode() {
        boolean scrambleMode = modeSeg.getSelectedIndex() == 0;
        btnRun.setText(scrambleMode ? R.string.run_scramble : R.string.run_restore);
        tipText.setText(scrambleMode ? R.string.tip_scramble : R.string.tip_restore);
        emptyText.setText(R.string.pick_hint);
        autoRow.setVisibility(scrambleMode ? View.GONE : View.VISIBLE);
        if (!scrambleMode && tagFormat < 0) {
            // 网络流传的混淆图基本都是单次混淆，还原时默认 1 次
            strengthSeg.setSelectedIndex(0);
        }
        updateFormatUi();
        resultCard.setVisibility(View.GONE);
        dstImage.setImageDrawable(null);
        if (resultBitmap != null) {
            resultBitmap.recycle();
            resultBitmap = null;
        }
        resultBytes = null;
        savedUri = null;
    }

    /** MIUI 观感的下滑面板：圆角顶角 + 拖拽条 + 行内说明 + 蓝色对勾。 */
    private void showFormatDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_format_picker);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setWindowAnimations(R.style.SheetAnimation);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.45f;
            window.setAttributes(params);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        LinearLayout list = dialog.findViewById(R.id.formatList);
        String[] descriptions = getResources().getStringArray(R.array.format_desc);
        for (int i = 0; i < PopularCodecs.FORMAT_COUNT; i++) {
            if (i > 0) list.addView(buildSheetDivider());
            list.addView(buildFormatRow(dialog, i, i < descriptions.length ? descriptions[i] : ""));
        }
        dialog.findViewById(R.id.sheetCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        final View root = dialog.findViewById(R.id.sheetRoot);
        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface d) {
                int bottom = 0;
                Window w = dialog.getWindow();
                if (w != null) {
                    WindowInsets insets = w.getDecorView().getRootWindowInsets();
                    if (insets != null) bottom = insets.getSystemWindowInsetBottom();
                }
                root.setPadding(0, dp(8), 0, Math.max(dp(6), bottom));
            }
        });
        dialog.show();
        capSheetHeight(dialog);
    }

    private View buildFormatRow(final Dialog dialog, final int format, String description) {
        boolean selected = format == this.format;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(13), dp(18), dp(13));
        row.setBackgroundResource(R.drawable.ripple_row);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(PopularCodecs.name(format));
        name.setTextSize(15f);
        name.setTextColor(getColor(selected ? R.color.mi_blue : R.color.text_primary));
        if (selected) name.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(name);

        if (description != null && description.length() > 0) {
            TextView desc = new TextView(this);
            desc.setText(description);
            desc.setTextSize(12.5f);
            desc.setTextColor(getColor(R.color.text_secondary));
            LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            descParams.topMargin = dp(3);
            desc.setLayoutParams(descParams);
            texts.addView(desc);
        }
        row.addView(texts);

        ImageView check = new ImageView(this);
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(dp(20), dp(20));
        checkParams.leftMargin = dp(12);
        check.setLayoutParams(checkParams);
        check.setImageResource(R.drawable.ic_check);
        check.setColorFilter(getColor(R.color.mi_blue));
        check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        row.addView(check);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.this.format = format;
                tagFormat = -1;
                cropWidth = 0;
                cropHeight = 0;
                updateFormatUi();
                dialog.dismiss();
            }
        });
        return row;
    }

    private View buildSheetDivider() {
        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(0.5f))));
        divider.setBackgroundResource(R.drawable.divider_inset);
        return divider;
    }

    /** 选项多的时候限制列表高度，避免面板顶到刘海。 */
    private void capSheetHeight(Dialog dialog) {
        final ScrollView scroll = dialog.findViewById(R.id.formatScroll);
        if (scroll == null) return;
        final int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.52f);
        scroll.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                if (scroll.getHeight() > max) {
                    ViewGroup.LayoutParams params = scroll.getLayoutParams();
                    params.height = max;
                    scroll.setLayoutParams(params);
                }
                return true;
            }
        });
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void updateFormatUi() {
        formatValue.setText(PopularCodecs.name(format));
        int hint;
        int desc;
        if (format == PopularCodecs.FORMAT_TOMATO) {
            hint = R.string.key_hint_tomato;
            desc = R.string.key_desc_tomato;
        } else if (PopularCodecs.usesNumericKey(format)) {
            hint = R.string.key_hint_numeric;
            desc = R.string.key_desc_numeric;
        } else if (format == PopularCodecs.FORMAT_ETA) {
            hint = R.string.key_hint;
            desc = R.string.key_desc;
        } else {
            hint = R.string.key_hint_popular;
            desc = R.string.key_desc_popular;
        }
        keyInput.setHint(hint);
        keyDesc.setText(desc);
        boolean needsKey = PopularCodecs.needsKey(format);
        keyInput.setEnabled(needsKey);
        if (!needsKey && keyInput.getText().length() > 0) keyInput.setText("");
    }

    private void toggleKeyVisible() {
        keyVisible = !keyVisible;
        keyInput.setTransformationMethod(keyVisible
                ? HideReturnsTransformationMethod.getInstance()
                : PasswordTransformationMethod.getInstance());
        updateKeyToggle();
    }

    private void updateKeyToggle() {
        btnKeyToggle.setText(keyVisible ? R.string.key_toggle_hide : R.string.key_toggle_show);
    }

    // ---------------------------------------------------------------- 选图

    private void pickImage() {
        if (busy) {
            toast(getString(R.string.busy));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.card_image)), REQ_PICK);
        } catch (Exception e) {
            toast(getString(R.string.err_pick));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) loadImage(uri);
        }
    }

    private void loadImage(final Uri uri) {
        final int maxPixels = modeSeg.getSelectedIndex() == 1
                ? Math.max(MAX_PIXELS, Math.min(RESTORE_MAX_PIXELS, (int) (Runtime.getRuntime().maxMemory() / 12L)))
                : MAX_PIXELS;
        final int maxEdge = modeSeg.getSelectedIndex() == 1 ? RESTORE_MAX_EDGE : MAX_EDGE;
        setBusy(true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String failure = null;
                Decoded decoded = null;
                String tag = null;
                try {
                    byte[] head = readHead(uri, HEAD_BYTES);
                    if (head != null) tag = PngMeta.readText(head);
                    decoded = decode(uri, head, maxPixels, maxEdge);
                } catch (Throwable t) {
                    Log.w(TAG, "读取图片失败: " + uri, t);
                    failure = describe(t);
                }
                final Decoded result = decoded;
                final String message = failure;
                final String tagValue = tag;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (result == null) {
                            setBusy(false);
                            toast(message == null ? getString(R.string.err_pick) : message);
                            return;
                        }
                        applyPicked(result, tagValue);
                    }
                });
            }
        }).start();
    }

    /** 打开内容流，失败时给出可诊断的异常。 */
    private InputStream open(Uri uri) throws Exception {
        InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) throw new IOException("open-failed");
        return is;
    }

    private void close(InputStream is) {
        try {
            if (is != null) is.close();
        } catch (Exception ignored) {
            // 忽略
        }
    }

    /** 只读文件头部若干字节，用于解析 PNG 参数标记与 EXIF。 */
    private byte[] readHead(Uri uri, int limit) {
        InputStream is = null;
        try {
            is = open(uri);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int total = 0;
            while (total < limit) {
                int n = is.read(buf, 0, Math.min(buf.length, limit - total));
                if (n <= 0) break;
                bo.write(buf, 0, n);
                total += n;
            }
            return bo.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "读取文件头失败", t);
            return null;
        } finally {
            close(is);
        }
    }

    /** 两遍流式解码：先读尺寸算采样率，再按需解码，全程不缓存整张原图。 */
    private Decoded decode(Uri uri, byte[] head, int maxPixels, int maxEdge) throws Exception {
        long startedAt = SystemClock.elapsedRealtime();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream first = open(uri);
        try {
            BitmapFactory.decodeStream(first, null, bounds);
        } finally {
            close(first);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("unsupported-format");
        }
        int sample = 1;
        while ((long) (bounds.outWidth / sample) * (bounds.outHeight / sample) > maxPixels
                || Math.max(bounds.outWidth, bounds.outHeight) / sample > maxEdge) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        InputStream second = open(uri);
        Bitmap bitmap;
        try {
            bitmap = BitmapFactory.decodeStream(second, null, opts);
        } finally {
            close(second);
        }
        if (bitmap == null) {
            throw new IOException("decode-failed");
        }
        if (head != null) {
            bitmap = applyExif(bitmap, head);
        }
        Decoded decoded = new Decoded();
        decoded.bitmap = bitmap;
        decoded.srcWidth = bounds.outWidth;
        decoded.srcHeight = bounds.outHeight;
        Log.i(TAG, "解码 " + bounds.outWidth + "x" + bounds.outHeight + " → " + bitmap.getWidth() + "x"
                + bitmap.getHeight() + "（采样 1/" + sample + "）用时 " + (SystemClock.elapsedRealtime() - startedAt) + " ms");
        return decoded;
    }

    /** 把底层异常翻译成用户能看懂、也便于排查的提示。 */
    private String describe(Throwable t) {
        if (t instanceof OutOfMemoryError) return getString(R.string.err_oom);
        if (t instanceof SecurityException) return getString(R.string.err_permission);
        String m = String.valueOf(t.getMessage());
        if ("unsupported-format".equals(m)) return getString(R.string.err_format);
        if ("decode-failed".equals(m)) return getString(R.string.err_decode);
        if ("open-failed".equals(m) || t instanceof java.io.FileNotFoundException) {
            return getString(R.string.err_unavailable);
        }
        return getString(R.string.err_read, m);
    }

    private void applyPicked(Decoded decoded, String tag) {
        setBusy(false);
        if (srcBitmap != null && srcBitmap != decoded.bitmap) {
            srcBitmap.recycle();
        }
        srcBitmap = decoded.bitmap;
        srcImage.setImageBitmap(srcBitmap);
        srcImage.setVisibility(View.VISIBLE);
        emptyHint.setVisibility(View.GONE);

        boolean scaled = srcBitmap.getWidth() != decoded.srcWidth || srcBitmap.getHeight() != decoded.srcHeight;
        srcInfo.setText(scaled
                ? getString(R.string.image_scaled, srcBitmap.getWidth(), srcBitmap.getHeight())
                : getString(R.string.image_info, srcBitmap.getWidth(), srcBitmap.getHeight()));
        if (scaled && modeSeg.getSelectedIndex() == 1) {
            toast(getString(R.string.warn_scaled_restore));
        }

        resultCard.setVisibility(View.GONE);
        dstImage.setImageDrawable(null);
        resultBytes = null;
        savedUri = null;

        tagFormat = -1;
        cropWidth = 0;
        cropHeight = 0;
        if (modeSeg.getSelectedIndex() == 1) {
            int current = strengthSeg.getSelectedIndex() + 1;
            if (tag != null) {
                int fmt = PngMeta.getInt(tag, "fmt", -1);
                int t = PngMeta.getInt(tag, "times", PngMeta.getInt(tag, "passes", 0));
                int ow = PngMeta.getInt(tag, "w", 0);
                int oh = PngMeta.getInt(tag, "h", 0);
                if (fmt >= 0 && fmt < PopularCodecs.FORMAT_COUNT) {
                    tagFormat = fmt;
                    format = fmt;
                }
                if (t > 0) strengthSeg.setSelectedIndex(Math.min(t, 3) - 1);
                if (ow > 0 && oh > 0) {
                    cropWidth = ow;
                    cropHeight = oh;
                }
                updateFormatUi();
                toast(getString(R.string.tag_loaded,
                        PopularCodecs.name(tagFormat >= 0 ? tagFormat : format), t > 0 ? t : current));
            } else {
                toast(getString(R.string.tag_none));
            }
        }
    }

    private Bitmap applyExif(Bitmap bitmap, byte[] data) {
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(data));
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int degrees = 0;
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
            if (degrees == 0) return bitmap;
            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) bitmap.recycle();
            return rotated;
        } catch (Throwable t) {
            return bitmap;
        }
    }

    // ---------------------------------------------------------------- 处理

    private void startTask() {
        if (busy) {
            toast(getString(R.string.busy));
            return;
        }
        if (srcBitmap == null) {
            toast(getString(R.string.err_no_image));
            return;
        }
        final boolean scrambleMode = modeSeg.getSelectedIndex() == 0;
        final boolean tagged = tagFormat >= 0;
        final boolean autoDetect = !scrambleMode && autoSwitch.isChecked() && !tagged;
        final String key = keyInput.getText().toString();
        final int times = strengthSeg.getSelectedIndex() + 1;
        final boolean writeTag = tagSwitch.isChecked();
        final int fmt = tagged ? tagFormat : format;
        final int cropW = cropWidth;
        final int cropH = cropHeight;
        final Bitmap source = srcBitmap;

        setBusy(true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                long started = SystemClock.elapsedRealtime();
                int w = source.getWidth();
                int h = source.getHeight();
                Bitmap output = null;
                byte[] bytes = null;
                String error = null;
                String note = null;
                try {
                    long t0 = SystemClock.elapsedRealtime();
                    int[] pixels = new int[w * h];
                    source.getPixels(pixels, 0, w, 0, 0, w, h);
                    long t1 = SystemClock.elapsedRealtime();
                    int usedFormat = fmt;
                    PopularCodecs.Result result;
                    if (scrambleMode) {
                        result = PopularCodecs.transform(fmt, pixels, w, h, key, times, true);
                    } else if (autoDetect) {
                        int[] chosen = new int[1];
                        double[] score = new double[1];
                        result = PopularCodecs.autoRestore(pixels, w, h, key, times, chosen, score);
                        usedFormat = chosen[0];
                        note = PopularCodecs.name(usedFormat);
                    } else {
                        result = PopularCodecs.transform(fmt, pixels, w, h, key, times, false);
                    }
                    long t2 = SystemClock.elapsedRealtime();
                    int outW = result.width;
                    int outH = result.height;
                    int[] outPixels = result.pixels;
                    if (!scrambleMode && cropW > 0 && cropH > 0 && (outW != cropW || outH != cropH)) {
                        outPixels = PopularCodecs.cropRegion(outPixels, outW, outH, cropW, cropH);
                        outW = cropW;
                        outH = cropH;
                    }
                    output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                    output.setPixels(outPixels, 0, outW, 0, 0, outW, outH);
                    long t3 = SystemClock.elapsedRealtime();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    if (!output.compress(Bitmap.CompressFormat.PNG, 100, bo)) {
                        throw new IllegalStateException("PNG 编码失败");
                    }
                    bytes = bo.toByteArray();
                    long t4 = SystemClock.elapsedRealtime();
                    if (writeTag) {
                        bytes = PngMeta.insertText(bytes, PngMeta.buildTag(usedFormat, times, w, h));
                    }
                    long t5 = SystemClock.elapsedRealtime();
                    Log.i(TAG, (scrambleMode ? "混淆 " : "还原 ") + w + "x" + h + " 格式=" + usedFormat
                            + " 次数=" + times + "（输出 " + outW + "x" + outH + "）| 取像素 " + (t1 - t0)
                            + "，算法 " + (t2 - t1) + "，位图 " + (t3 - t2) + "，PNG 编码 " + (t4 - t3)
                            + "，标记 " + (t5 - t4) + "，合计 " + (t5 - started) + " ms");
                } catch (Throwable t) {
                    error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    bytes = null;
                    if (output != null) {
                        output.recycle();
                        output = null;
                    }
                }
                final Bitmap done = output;
                final byte[] data = bytes;
                final String message = error;
                final String info = note;
                final long elapsed = SystemClock.elapsedRealtime() - started;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        finishTask(scrambleMode, done, data, message, elapsed, writeTag, info);
                    }
                });
            }
        }).start();
    }

    private void finishTask(boolean scrambleMode, Bitmap bitmap, byte[] bytes, String error, long elapsed,
                            boolean wroteTag, String note) {
        setBusy(false);
        if (bitmap == null || bytes == null) {
            toast(getString(R.string.err_save, error == null ? getString(R.string.err_unknown) : error));
            return;
        }
        if (resultBitmap != null) {
            resultBitmap.recycle();
        }
        resultBitmap = bitmap;
        resultBytes = bytes;
        savedUri = null;
        pendingName = (scrambleMode ? "混淆图_" : "还原图_")
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";

        dstImage.setImageBitmap(bitmap);
        String meta = getString(R.string.result_meta, bitmap.getWidth(), bitmap.getHeight(),
                elapsed + " ms · " + human(bytes.length)
                        + (note != null ? " · " + getString(R.string.auto_detected, note) : "")
                        + (wroteTag && scrambleMode ? getString(R.string.meta_tagged) : ""));
        resultInfo.setText(meta);
        resultCard.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- 保存 / 分享

    private void saveResult(boolean thenShare) {
        if (resultBytes == null) {
            toast(getString(R.string.err_no_image));
            return;
        }
        if (thenShare && savedUri != null) {
            share(savedUri);
            return;
        }
        pendingShare = thenShare;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
            return;
        }
        doSave();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                doSave();
            } else {
                pendingShare = false;
                toast(getString(R.string.no_permission));
            }
        }
    }

    private void doSave() {
        if (resultBytes == null) return;
        final byte[] bytes = resultBytes;
        final String name = pendingName;
        setBusy(true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                Uri uri = null;
                String error = null;
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        uri = saveScoped(name, bytes);
                    } else {
                        uri = saveLegacy(name, bytes);
                    }
                } catch (Throwable t) {
                    error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                final Uri saved = uri;
                final String message = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        setBusy(false);
                        if (saved == null) {
                            pendingShare = false;
                            toast(getString(R.string.err_save, message == null ? getString(R.string.err_unknown) : message));
                            return;
                        }
                        savedUri = saved;
                        toast(getString(R.string.saved));
                        if (pendingShare) {
                            pendingShare = false;
                            share(saved);
                        }
                    }
                });
            }
        }).start();
    }

    private Uri saveScoped(String name, byte[] bytes) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/混淆图");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("媒体库拒绝写入");
        OutputStream os = getContentResolver().openOutputStream(uri);
        if (os == null) throw new IllegalStateException("无法打开输出流");
        try {
            os.write(bytes);
            os.flush();
        } finally {
            try {
                os.close();
            } catch (Exception ignored) {
                // 忽略
            }
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        getContentResolver().update(uri, done, null, null);
        return uri;
    }

    private Uri saveLegacy(String name, byte[] bytes) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "混淆图");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建目录");
        File file = new File(dir, name);
        FileOutputStream fos = new FileOutputStream(file);
        try {
            fos.write(bytes);
            fos.flush();
        } finally {
            try {
                fos.close();
            } catch (Exception ignored) {
                // 忽略
            }
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
        values.put(MediaStore.MediaColumns.DATA, file.getAbsolutePath());
        try {
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) return uri;
        } catch (Throwable ignored) {
            // 继续用扫描兜底
        }
        final Uri[] holder = new Uri[1];
        final Object lock = new Object();
        MediaScannerConnection.scanFile(getApplicationContext(), new String[] {file.getAbsolutePath()},
                new String[] {"image/png"}, new MediaScannerConnection.OnScanCompletedListener() {
                    @Override
                    public void onScanCompleted(String path, Uri uri) {
                        synchronized (lock) {
                            holder[0] = uri;
                            lock.notifyAll();
                        }
                    }
                });
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 2500L;
            while (holder[0] == null && System.currentTimeMillis() < deadline) {
                try {
                    lock.wait(150L);
                } catch (InterruptedException ignored) {
                    // 忽略
                }
            }
        }
        if (holder[0] != null) return holder[0];
        return queryLegacyUri(file);
    }

    private Uri queryLegacyUri(File file) {
        try {
            Cursor cursor = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[] {MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DATA + "=?", new String[] {file.getAbsolutePath()}, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        return Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                String.valueOf(cursor.getLong(0)));
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    private void share(Uri uri) {
        if (uri == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("image/png");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share)));
        } catch (Throwable t) {
            toast(getString(R.string.err_save, getString(R.string.err_unknown)));
        }
    }

    // ---------------------------------------------------------------- 小工具

    private void setBusy(boolean value) {
        busy = value;
        btnRun.setEnabled(!value);
        btnRun.setAlpha(value ? 0.55f : 1f);
        pickCard.setEnabled(!value);
        btnRun.setText(value ? R.string.running
                : (modeSeg.getSelectedIndex() == 0 ? R.string.run_scramble : R.string.run_restore));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static String human(long bytes) {
        if (bytes >= 1048576L) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        if (bytes >= 1024L) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }
}
