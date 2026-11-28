package com.secreto.space;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import org.json.JSONArray;
import org.json.JSONObject;

public class GameLibraryActivity extends Activity {
    private static final int BG = 0xFF101114;
    private static final int CARD = 0xFF202126;
    private static final int INDIGO = 0xFFD0BCFF;
    private static final int TEXT = 0xFFE5E1E6;
    private static final int SUB = 0xFFC9C5CA;
    private static final int SURFACE_2 = 0xFF2A2B31;
    private static final int BORDER = 0xFF46464F;

    private GridLayout grid;
    private TextView statusText;
    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // Top bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(16), dp(12), dp(16), dp(12));
        topBar.setBackgroundColor(CARD);

        TextView title = new TextView(this);
        title.setText("GAME SPACE");
        title.setTextSize(16);
        title.setTextColor(INDIGO);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        topBar.addView(title);

        TextView badge = new TextView(this);
        badge.setText("A50");
        badge.setTextSize(10);
        badge.setTextColor(SUB);
        badge.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(0xFF1A1D2A);
        badgeBg.setCornerRadius(dp(4));
        badge.setBackground(badgeBg);
        topBar.addView(badge);
        root.addView(topBar);

        // Status
        statusText = new TextView(this);
        statusText.setText("Loading games...");
        statusText.setTextColor(SUB);
        statusText.setTextSize(11);
        statusText.setPadding(dp(16), dp(8), dp(16), dp(4));
        root.addView(statusText);

        // Scroll + grid
        ScrollView scroll = new ScrollView(this);
        LinearLayout scrollInner = new LinearLayout(this);
        scrollInner.setOrientation(LinearLayout.VERTICAL);

        grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding(dp(12), dp(4), dp(12), dp(12));
        grid.setUseDefaultMargins(false);
        scrollInner.addView(grid);
        scroll.addView(scrollInner);
        root.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Bottom nav
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(CARD);
        nav.setPadding(dp(8), dp(8), dp(8), dp(8));
        String[] navLabels = {"GAMES", "BOOST", "NETWORK", "SETTINGS"};
        for (String l : navLabels) {
            TextView t = new TextView(this);
            t.setText(l);
            t.setTextSize(9);
            t.setTextColor(l.equals("GAMES") ? INDIGO : SUB);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(4), dp(6), dp(4), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            t.setLayoutParams(lp);
            nav.addView(t);
        }
        root.addView(nav);

        setContentView(root);
        loadGames();
    }

    private void loadGames() {
        new Thread(() -> {
            String res = IpcClient.sendCommand("list_games");
            handler.post(() -> renderGames(res));
        }).start();
    }

    private void renderGames(String res) {
        grid.removeAllViews();
        if (res == null) {
            statusText.setText("Daemon offline. Start secretod.");
            return;
        }
        try {
            JSONArray arr = new JSONArray(res);
            statusText.setText(arr.length() + " games in library");
            for (int i = 0; i < arr.length(); i++) {
                grid.addView(buildTile(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            statusText.setText("Parse error: " + e.getMessage());
        }
    }

    private View buildTile(JSONObject g) {
        String pkg = g.optString("pkg", "");
        String name = g.optString("name", pkg);
        String preset = g.optString("preset", "BALANCED").toUpperCase();
        boolean bbr = g.optBoolean("auto_bbr", false);

        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackgroundColor(CARD);
        GridLayout.LayoutParams tileLp = new GridLayout.LayoutParams();
        tileLp.width = 0;
        tileLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        tileLp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        tileLp.setMargins(dp(4), dp(4), dp(4), dp(4));
        tile.setLayoutParams(tileLp);

        // Cover
        LinearLayout cover = new LinearLayout(this);
        cover.setOrientation(LinearLayout.VERTICAL);
        cover.setGravity(Gravity.CENTER);
        cover.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(110)));
        int hue = Math.abs(pkg.hashCode()) % 360;
        GradientDrawable coverBg = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[]{ Color.HSVToColor(new float[]{hue, 0.6f, 0.35f}),
                       Color.HSVToColor(new float[]{(hue + 60) % 360, 0.5f, 0.15f}) });
        cover.setBackground(coverBg);

        ImageView icon = new ImageView(this);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));
        try {
            Drawable d = getPackageManager().getApplicationIcon(pkg);
            icon.setImageDrawable(d);
        } catch (PackageManager.NameNotFoundException e) {
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
        }
        cover.addView(icon);
        tile.addView(cover);

        // Body
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(10), dp(8), dp(10), dp(10));

        TextView nameTv = new TextView(this);
        nameTv.setText(name);
        nameTv.setTextColor(TEXT);
        nameTv.setTextSize(12);
        nameTv.setTypeface(null, android.graphics.Typeface.BOLD);
        nameTv.setMaxLines(1);
        body.addView(nameTv);

        TextView pkgTv = new TextView(this);
        pkgTv.setText(pkg);
        pkgTv.setTextColor(SUB);
        pkgTv.setTextSize(8);
        pkgTv.setMaxLines(1);
        body.addView(pkgTv);

        TextView profTv = new TextView(this);
        profTv.setText(g.optString("profile", "Balanced 455M"));
        profTv.setTextColor(INDIGO);
        profTv.setTextSize(9);
        body.addView(profTv);

        // Preset spinner
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
            new String[]{"BATTERY", "BALANCED", "PERFORMANCE", "ESPORTS"});
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(indexOfPreset(preset));
        sp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                String p = parent.getItemAtPosition(pos).toString();
                new Thread(() -> IpcClient.sendCommand("set_game_profile " + pkg + " " + p + " " + (bbr ? "1" : "0"))).start();
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        spLp.setMargins(0, dp(6), 0, 0);
        sp.setLayoutParams(spLp);
        body.addView(sp);

        // PLAY button
        Button play = new Button(this);
        play.setText("PLAY");
        play.setTextColor(0xFF000000);
        play.setTextSize(11);
        play.setTypeface(null, android.graphics.Typeface.BOLD);
        play.setBackgroundColor(INDIGO);
        play.setPadding(0, dp(6), 0, dp(6));
        GradientDrawable playBg = new GradientDrawable();
        playBg.setColor(INDIGO);
        playBg.setCornerRadius(dp(6));
        play.setBackground(playBg);
        play.setOnClickListener(v -> launchGame(pkg));
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        playLp.setMargins(0, dp(6), 0, 0);
        play.setLayoutParams(playLp);
        body.addView(play);

        tile.addView(body);
        return tile;
    }

    private int indexOfPreset(String p) {
        String[] arr = {"BATTERY", "BALANCED", "PERFORMANCE", "ESPORTS"};
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(p)) return i;
        return 1;
    }

    private void launchGame(String pkg) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) startActivity(i);
        } catch (Exception ignored) {}
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

