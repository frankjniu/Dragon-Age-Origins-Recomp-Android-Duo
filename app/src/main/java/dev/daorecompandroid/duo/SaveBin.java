package dev.daorecompandroid.duo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PLAN 10.24 (issue #8): deleting saves, and the recycle bin they go to. The 360 game has
 * no way to delete a character or a save, and its saves are in the app's own storage, out
 * of the player's reach.
 *
 * <p>Both pages list the characters - a character's saves are the folders
 * `0x<character>_<slot>_<u or a>!<n>` that share the id - newest first, each with a tick of
 * its own (all its saves) and its name, which opens its saves, newest first, to tick one by
 * one (the Picker). Before the game only (the Saves page), so none of them is in use.
 *
 * <p>Deleting moves the saves (each folder and its `.name`) to
 * `files/content/00000001/.deleted/<the time, ms>/`, one folder per deletion. A whole
 * character deleted takes their earlier copies in `.backup/` with them, into that
 * folder's `earlier/`, so Restore an earlier save cannot bring back part of a deleted
 * character. The leading dot keeps it out of the game's list (warden_xam.cc's
 * enumerator). The bin puts saves back - in their own slot, or the character's next free
 * one if it has been taken since - or deletes them for good, after an "are you sure?".
 */
final class SaveBin {

    private static final String TAG = "Warden";
    private static final Pattern SLOT = Pattern.compile("^(0x[0-9a-fA-F]+)_(\\d+)_([ua])!(\\d+)$");

    /** One save in a list: where it is, its display name's file, what it shows. */
    private static final class Entry {
        File folder;
        File display;     // <folder>.name, or null (an earlier copy has none)
        String slot;      // the folder's own name: where it goes back to
        String id;        // the character's id, or the folder's name if it has none
        String who;       // the character's name, or null
        String what;      // the chapter, the time written, autosave or earlier copy
        long time;
        boolean earlier;  // from .backup/
        File batch;       // in the bin: the deletion's folder
    }

    /** What a list's buttons do with the ticked saves. */
    private interface Action {
        void run(List<Entry> chosen, List<String> wholeCharacters);
    }

    private final Activity activity;
    private final File savesDir;
    private final Runnable back;

    SaveBin(Activity activity, File filesDir, Runnable back) {
        this.activity = activity;
        this.savesDir = new File(filesDir, "content/00000001");
        this.back = back;
    }

    private AlertDialog.Builder builder() {
        return new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    private File binDir() {
        return new File(savesDir, ".deleted");
    }

    // ---- the saves ----

    private static Entry entry(File folder, File display, boolean earlier) {
        File met = new File(folder, "Saves/savegame.met");
        if (!met.isFile()) {
            return null;
        }
        Entry e = new Entry();
        e.folder = folder;
        e.display = display != null && display.isFile() ? display : null;
        e.slot = folder.getName();
        Matcher m = SLOT.matcher(e.slot);
        e.id = m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : e.slot;
        String[] info = SaveRestore.info(met);
        e.who = info[0];
        e.time = new File(folder, "Saves/savegame.das").lastModified();
        String when = new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(new Date(e.time));
        e.what = (info[1].isEmpty() ? "A save" : info[1]) + "  (" + when + ")"
                + (m.matches() && m.group(3).equals("a") ? "  autosave" : "")
                + (earlier ? "  earlier copy" : "");
        e.earlier = earlier;
        return e;
    }

    /** The saves the game lists. */
    private List<Entry> live() {
        List<Entry> out = new ArrayList<>();
        File[] folders = savesDir.listFiles();
        if (folders != null) {
            for (File folder : folders) {
                if (folder.isDirectory() && !folder.getName().startsWith(".")) {
                    Entry e = entry(folder, new File(savesDir, folder.getName() + ".name"), false);
                    if (e != null) {
                        out.add(e);
                    }
                }
            }
        }
        return out;
    }

    /** The saves in the bin. */
    private List<Entry> binned() {
        List<Entry> out = new ArrayList<>();
        File[] batches = binDir().listFiles();
        if (batches == null) {
            return out;
        }
        for (File batch : batches) {
            if (!batch.isDirectory()) {
                continue;
            }
            for (int pass = 0; pass < 2; pass++) {
                File dir = pass == 0 ? batch : new File(batch, "earlier");
                File[] folders = dir.listFiles();
                if (folders == null) {
                    continue;
                }
                for (File folder : folders) {
                    if (!folder.isDirectory() || (pass == 0 && folder.getName().equals("earlier"))) {
                        continue;
                    }
                    Entry e = entry(folder, new File(dir, folder.getName() + ".name"), pass == 1);
                    if (e != null) {
                        e.batch = batch;
                        out.add(e);
                    }
                }
            }
        }
        return out;
    }

    /** How many saves are in the bin (the Saves page's row). */
    int count() {
        return binned().size();
    }

    // ---- the list ----

    /**
     * Two pages in one: the characters, then - a character's name chosen - that character's
     * saves. Each row has its own tick, apart from its name, so a character can be ticked
     * whole or opened; ticks are kept going between the pages, and the buttons act on every
     * one. Newest first on both pages: the characters by their newest save, the saves by when
     * they were written. Rows are views of their own (not a ListView) so the d-pad moves
     * between a row's tick and its name.
     */
    private final class Picker {
        final String title;
        final String positive;
        final Action onPositive;
        final String neutral;
        final Action onNeutral;
        final Map<String, List<Entry>> groups = new LinkedHashMap<>();
        final Map<String, String> names = new LinkedHashMap<>();
        final List<Entry> all;
        final Set<Entry> ticked = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Runnable> refreshers = new ArrayList<>();
        final Context ctx = builder().getContext();
        AlertDialog dialog;
        int charactersScroll;
        String cameFrom;
        boolean byKeys;

        Picker(String title, List<Entry> entries, String positive, Action onPositive,
               String neutral, Action onNeutral) {
            this.title = title;
            this.positive = positive;
            this.onPositive = onPositive;
            this.neutral = neutral;
            this.onNeutral = onNeutral;
            all = new ArrayList<>(entries);
            all.sort((a, b) -> Long.compare(b.time, a.time));
            for (Entry e : all) {
                groups.computeIfAbsent(e.id, k -> new ArrayList<>()).add(e);
                if (e.who != null && !names.containsKey(e.id)) {
                    names.put(e.id, e.who);
                }
            }
        }

        String name(String id) {
            String who = names.get(id);
            return who != null ? who : "Unnamed character (" + id + ")";
        }

        int tickedIn(List<Entry> list) {
            int n = 0;
            for (Entry e : list) {
                n += ticked.contains(e) ? 1 : 0;
            }
            return n;
        }

        void flip(List<Entry> list) {
            if (tickedIn(list) == list.size()) {
                ticked.removeAll(list);
            } else {
                ticked.addAll(list);
            }
            refresh();
        }

        void refresh() {
            for (Runnable r : refreshers) {
                r.run();
            }
            if (dialog == null) {
                return;
            }
            int n = ticked.size();
            Button p = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            p.setText(n == 0 ? positive : positive + " " + n);
            p.setEnabled(n > 0);
            if (neutral != null) {
                Button b = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
                b.setText(n == 0 ? neutral : neutral + " (" + n + ")");
                b.setEnabled(n > 0);
            }
        }

        /** The first page: everything, then each character, newest first. */
        void showCharacters() {
            LinearLayout rows = page();
            View[] focus = new View[1];
            addRow(rows, () -> bold("Everything (" + plural(all.size()) + ")"),
                    () -> ticked.size() == all.size(), () -> flip(all), null);
            for (Map.Entry<String, List<Entry>> g : groups.entrySet()) {
                String id = g.getKey();
                List<Entry> saves = g.getValue();
                View label = addRow(rows, () -> {
                    int t = tickedIn(saves);
                    String detail = plural(saves.size()) + ", last " + when(saves.get(0).time)
                            + (t > 0 && t < saves.size() ? " - " + t + " ticked" : "");
                    SpannableString s = new SpannableString(name(id) + "\n" + detail);
                    s.setSpan(new StyleSpan(Typeface.BOLD), 0, name(id).length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    s.setSpan(new RelativeSizeSpan(0.85f), name(id).length(), s.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    return s;
                }, () -> tickedIn(saves) == saves.size(), () -> flip(saves), () -> {
                    charactersScroll = ((ScrollView) rows.getParent()).getScrollY();
                    byKeys = !rows.isInTouchMode();
                    cameFrom = id;
                    showCharacter(id);
                });
                if (id.equals(cameFrom)) {
                    focus[0] = label;
                }
            }
            open(title, rows, back::run, charactersScroll, focus[0]);
        }

        /** A character's page: their saves, newest first. */
        void showCharacter(String id) {
            List<Entry> saves = groups.get(id);
            LinearLayout rows = page();
            addRow(rows, () -> bold("All of " + name(id) + "'s saves (" + plural(saves.size()) + ")"),
                    () -> tickedIn(saves) == saves.size(), () -> flip(saves), null);
            for (Entry e : saves) {
                addRow(rows, () -> e.what, () -> ticked.contains(e), () -> {
                    if (!ticked.remove(e)) {
                        ticked.add(e);
                    }
                    refresh();
                }, null);
            }
            open(name(id), rows, this::showCharacters, 0, null);
        }

        LinearLayout page() {
            refreshers.clear();
            LinearLayout rows = new LinearLayout(ctx);
            rows.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(8);
            rows.setPadding(dp(16), pad, dp(16), pad);
            return rows;
        }

        /**
         * One row: a tick and a name. The name opens the row when it has somewhere to go
         * (a character, with a "›"), else it ticks too. Returns the name's view.
         */
        View addRow(LinearLayout rows, Supplier<CharSequence> text, Supplier<Boolean> isTicked,
                    Runnable tick, Runnable opens) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(56));
            CheckBox box = new CheckBox(ctx);
            box.setOnClickListener(v -> tick.run());
            row.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView label = new TextView(ctx);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            TypedValue colour = new TypedValue();
            if (ctx.getTheme().resolveAttribute(android.R.attr.textColorPrimary, colour, true)) {
                label.setTextColor(ctx.getColorStateList(colour.resourceId));
            }
            label.setPadding(dp(12), dp(10), dp(12), dp(10));
            label.setFocusable(true);
            label.setClickable(true);
            TypedValue bg = new TypedValue();
            ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, bg, true);
            label.setBackgroundResource(bg.resourceId);
            label.setOnClickListener(v -> (opens != null ? opens : tick).run());
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (opens != null) {
                TextView chevron = new TextView(ctx);
                chevron.setText("›");
                chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
                chevron.setPadding(dp(8), 0, dp(4), 0);
                chevron.setOnClickListener(v -> opens.run());
                row.addView(chevron);
            }
            rows.addView(row);
            refreshers.add(() -> {
                box.setChecked(isTicked.get());
                label.setText(text.get());
            });
            return label;
        }

        void open(String heading, LinearLayout rows, Runnable onBack, int scrollY, View focus) {
            if (dialog != null) {
                dialog.setOnDismissListener(null);
                dialog.dismiss();
            }
            ScrollView scroll = new ScrollView(ctx);
            scroll.addView(rows);
            AlertDialog.Builder b = builder().setTitle(heading).setView(scroll)
                    .setPositiveButton(positive, (d, w) -> act(onPositive))
                    .setNegativeButton("Back", (d, w) -> onBack.run())
                    .setOnCancelListener(d -> onBack.run());
            if (neutral != null) {
                b.setNeutralButton(neutral, (d, w) -> act(onNeutral));
            }
            dialog = b.show();
            refresh();
            scroll.post(() -> scroll.scrollTo(0, scrollY));
            if (focus != null && byKeys) {
                // Back on the characters from one opened with the d-pad: its place is that
                // character's name. Once the window has focus (a new window starts in
                // touch mode, where a plain request is refused).
                scroll.getViewTreeObserver().addOnWindowFocusChangeListener(
                        new ViewTreeObserver.OnWindowFocusChangeListener() {
                            @Override
                            public void onWindowFocusChanged(boolean has) {
                                if (has) {
                                    scroll.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
                                    focus.requestFocusFromTouch();
                                }
                            }
                        });
            }
        }

        void act(Action action) {
            List<Entry> chosen = new ArrayList<>();
            for (Entry e : all) {
                if (ticked.contains(e)) {
                    chosen.add(e);
                }
            }
            List<String> whole = new ArrayList<>();
            for (Map.Entry<String, List<Entry>> g : groups.entrySet()) {
                if (tickedIn(g.getValue()) == g.getValue().size()) {
                    whole.add(g.getKey());
                }
            }
            action.run(chosen, whole);
        }
    }

    private void choose(String title, List<Entry> entries, String empty,
                        String positive, Action onPositive, String neutral, Action onNeutral) {
        if (entries.isEmpty()) {
            builder().setTitle(title).setMessage(empty)
                    .setPositiveButton("Back", (d, w) -> back.run())
                    .setOnCancelListener(d -> back.run())
                    .show();
            return;
        }
        new Picker(title, entries, positive, onPositive, neutral, onNeutral).showCharacters();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static String when(long time) {
        return new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(new Date(time));
    }

    private static CharSequence bold(String text) {
        SpannableString s = new SpannableString(text);
        s.setSpan(new StyleSpan(Typeface.BOLD), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private static String plural(int n) {
        return n == 1 ? "1 save" : n + " saves";
    }

    // ---- deleting ----

    /** The Saves page's Delete saves. */
    void showDelete() {
        choose("Delete saves", live(), "There are no saves yet.",
                "Delete", this::delete, null, null);
    }

    private void delete(List<Entry> chosen, List<String> whole) {
        File batch = new File(binDir(), Long.toString(System.currentTimeMillis()));
        int moved = 0;
        for (Entry e : chosen) {
            moved += move(e.folder, e.display, batch) ? 1 : 0;
        }
        // A whole character's earlier copies go with them.
        int earlier = 0;
        File[] backups = new File(savesDir, ".backup").listFiles();
        if (backups != null && !whole.isEmpty()) {
            for (File backup : backups) {
                Matcher m = SLOT.matcher(backup.getName());
                if (backup.isDirectory() && m.matches()
                        && whole.contains(m.group(1).toLowerCase(Locale.ROOT))) {
                    earlier += move(backup, null, new File(batch, "earlier")) ? 1 : 0;
                }
            }
        }
        Log.i(TAG, "saves: " + moved + " of " + chosen.size() + " moved to the recycle bin ("
                + batch.getName() + ", " + whole.size() + " whole character(s))");
        message(moved == chosen.size()
                ? plural(moved) + (earlier == 0 ? "" : " and " + (earlier == 1
                        ? "1 earlier copy the game kept" : earlier + " earlier copies the game kept"))
                        + " moved to the recycle bin. Recycle bin, on the Saves page, puts"
                        + (moved + earlier == 1 ? " it" : " them") + " back."
                : moved + " of " + chosen.size() + " moved to the recycle bin; the rest could not be moved.");
    }

    private static boolean move(File folder, File display, File to) {
        to.mkdirs();
        if (!folder.renameTo(new File(to, folder.getName()))) {
            Log.w(TAG, "saves: " + folder + " not moved to " + to);
            return false;
        }
        if (display != null && display.isFile()
                && !display.renameTo(new File(to, display.getName()))) {
            Log.w(TAG, "saves: " + display + " not moved to " + to);
        }
        return true;
    }

    // ---- the bin ----

    /** The Saves page's Recycle bin. */
    void showBin() {
        choose("Recycle bin", binned(), "The recycle bin is empty. Deleted saves wait here until"
                        + " you put them back or delete them for good.",
                "Restore", (chosen, whole) -> restore(chosen),
                "Delete for good", (chosen, whole) -> confirmPurge(chosen));
    }

    private void restore(List<Entry> chosen) {
        int restored = 0;
        int moved = 0;
        // Newest first: when two want the same slot (a save and its earlier copy), the
        // newer gets it back and the other the character's next slot.
        chosen.sort((a, b) -> Long.compare(b.time, a.time));
        for (Entry e : chosen) {
            File target = new File(savesDir, e.slot);
            String slot = e.slot;
            if (target.exists()) {
                slot = freeSlot(e.slot);
                target = new File(savesDir, slot);
                moved++;
            }
            savesDir.mkdirs();
            if (!e.folder.renameTo(target)) {
                Log.w(TAG, "saves: " + e.folder + " not restored");
                continue;
            }
            File name = new File(savesDir, slot + ".name");
            if (e.display == null || !e.display.renameTo(name)) {
                writeName(name, (e.who != null ? e.who + " - " : "") + e.what.split("  ")[0]);
            }
            restored++;
            Log.i(TAG, "saves: " + e.slot + " restored from the recycle bin as " + slot);
            tidy(e.batch);
        }
        message(restored == 0 ? "Nothing could be restored."
                : plural(restored) + " restored. " + (restored == 1 ? "It is" : "They are")
                        + " in the game's Load Game list."
                        + (moved == 0 ? "" : restored == 1
                                ? " Its own slot had been taken since, so it is in the character's next slot."
                                : " " + (moved == 1 ? "One save's own slot had been taken since, so it is"
                                        : moved + " saves' own slots had been taken since, so they are")
                                        + " in the character's next slot."));
    }

    private void confirmPurge(List<Entry> chosen) {
        builder().setTitle("Are you sure?")
                .setMessage("Delete " + (chosen.size() == 1 ? "this save" : "these " + chosen.size() + " saves")
                        + " for good? " + (chosen.size() == 1 ? "It" : "They")
                        + " cannot be brought back.")
                .setPositiveButton("Delete for good", (d, w) -> {
                    int gone = 0;
                    for (Entry e : chosen) {
                        deleteTree(e.folder);
                        if (e.display != null) {
                            e.display.delete();
                        }
                        gone += e.folder.exists() ? 0 : 1;
                        tidy(e.batch);
                    }
                    Log.i(TAG, "saves: " + gone + " deleted for good from the recycle bin");
                    message(plural(gone) + " deleted for good.");
                })
                .setNegativeButton("Cancel", (d, w) -> showBin())
                .setOnCancelListener(d -> showBin())
                .show();
    }

    /** A deletion's folder removed once nothing is left in it. */
    private static void tidy(File batch) {
        new File(batch, "earlier").delete();  // only if empty
        batch.delete();
    }

    /** The character's next manual slot (`_<n>_u!0`, one past their highest). */
    private String freeSlot(String like) {
        Matcher m = SLOT.matcher(like);
        String character = m.matches() ? m.group(1) : "0x10000000";
        int highest = 0;
        File[] folders = savesDir.listFiles();
        if (folders != null) {
            for (File folder : folders) {
                Matcher f = SLOT.matcher(folder.getName());
                if (f.matches() && f.group(1).equalsIgnoreCase(character)) {
                    highest = Math.max(highest, Integer.parseInt(f.group(2)));
                }
            }
        }
        return character + "_" + (highest + 1) + "_u!0";
    }

    /** A display name as the game keeps them: UTF-16 big-endian in 256 bytes. */
    private static void writeName(File file, String text) {
        byte[] out = new byte[256];
        byte[] encoded = text.getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
        System.arraycopy(encoded, 0, out, 0, Math.min(encoded.length, 254));
        try (OutputStream o = new FileOutputStream(file)) {
            o.write(out);
        } catch (Exception e) {
            Log.w(TAG, "saves: " + file + " not written", e);
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
    }

    private void message(String text) {
        builder().setMessage(text)
                .setPositiveButton("OK", (d, w) -> back.run())
                .setOnCancelListener(d -> back.run())
                .show();
    }
}
