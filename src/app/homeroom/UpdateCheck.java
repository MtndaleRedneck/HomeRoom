package app.homeroom;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks the project's GitHub releases once per cold start and offers
 * Update / Later / Skip when a newer version exists. Silent on any failure.
 */
final class UpdateCheck {

    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/MtndaleRedneck/HomeRoom/releases/latest";
    private static final String PREFS = "homeroom";
    private static final String KEY_SKIPPED = "skipped_version";
    private static final int MAX_NOTES_CHARS = 4000;

    private UpdateCheck() {
    }

    static void checkInBackground(Activity activity) {
        final WeakReference<Activity> ref = new WeakReference<>(activity);
        new Thread(() -> {
            try {
                String current = currentVersion(ref.get());
                if (current == null) {
                    return;
                }
                HttpURLConnection conn = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "HomeRoom-Android");
                if (conn.getResponseCode() != 200) {
                    conn.disconnect();
                    return;
                }
                StringBuilder body = new StringBuilder();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        body.append(line);
                    }
                }
                conn.disconnect();

                JSONObject release = new JSONObject(body.toString());
                String tag = release.optString("tag_name", "");
                String page = release.optString("html_url", "");
                String notes = release.optString("body", "");
                if (tag.isEmpty() || page.isEmpty() || !isNewer(tag, current)) {
                    return;
                }
                Activity act = ref.get();
                if (act == null) {
                    return;
                }
                SharedPreferences prefs = act.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
                if (tag.equals(prefs.getString(KEY_SKIPPED, null))) {
                    return;
                }
                if (!act.isFinishing() && !act.isDestroyed()) {
                    act.runOnUiThread(() -> showDialog(act, tag, page, notes));
                }
            } catch (Exception ignored) {
                // No connection, malformed reply, or no release yet: stay quiet.
            }
        }, "update-check").start();
    }

    private static void showDialog(Activity activity, String tag, String page, String notes) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (notes.length() > MAX_NOTES_CHARS) {
            notes = notes.substring(0, MAX_NOTES_CHARS);
        }
        TextView notesView = new TextView(activity);
        notesView.setText(notes.trim().isEmpty() ? tag : notes.trim());
        int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
        notesView.setPadding(pad, pad / 2, pad, 0);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(notesView);
        try {
            new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.update_title) + ": " + tag)
                    .setView(scroll)
                    .setPositiveButton(R.string.update_button, (d, w) -> {
                        try {
                            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(page)));
                        } catch (Exception ignored) {
                        }
                    })
                    .setNegativeButton(R.string.update_later, null)
                    .setNeutralButton(R.string.update_skip, (d, w) ->
                            activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                                    .edit().putString(KEY_SKIPPED, tag).apply())
                    .show();
        } catch (Exception ignored) {
            // The activity went away between the check and show(): skip the prompt.
        }
    }

    private static String currentVersion(Activity activity) {
        try {
            PackageInfo info = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            return info.versionName;
        } catch (Exception e) {
            return null;
        }
    }

    /** Numeric, segment-wise comparison; ignores a leading "v" and suffixes like "-rc1". */
    static boolean isNewer(String candidate, String current) {
        int[] a = segments(candidate);
        int[] b = segments(current);
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int[] segments(String version) {
        String clean = version.replaceFirst("^[vV]", "");
        String[] parts = clean.split("[^0-9]+");
        int count = 0;
        for (String p : parts) {
            if (!p.isEmpty()) {
                count++;
            }
        }
        int[] out = new int[count];
        int i = 0;
        for (String p : parts) {
            if (!p.isEmpty()) {
                try {
                    out[i++] = Integer.parseInt(p);
                } catch (NumberFormatException e) {
                    out[i++] = 0;
                }
            }
        }
        return out;
    }
}
