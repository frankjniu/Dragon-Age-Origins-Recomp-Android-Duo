package dev.daorecompandroid.duo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.widget.Button;
import android.widget.ListView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PLAN 10.24 (issue #8): deleting saves, and the recycle bin they go to. The 360 game has
 * no way to delete a character or a save, and its saves are in the app's own storage, out
 * of the player's reach.
 *
 * <p>Both pages are one list of the saves grouped by character - a character's saves are
 * the folders `0x<character>_<slot>_<u or a>!<n>` that share the id - with a row for the
 * character above them and a row for everything at the top. Ticking a character ticks all
 * its saves; ticking every save of one ticks the character. Before the game only (the
 * Saves page), so none of them is in use.
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
     * The grouped list: everything, then each character (newest first) and their saves
     * (newest first). The buttons say how many are ticked, and are off while none is.
     */
    private void choose(String title, List<Entry> entries, String empty,
                        String positive, Action onPositive, String neutral, Action onNeutral) {
        if (entries.isEmpty()) {
            builder().setTitle(title).setMessage(empty)
                    .setPositiveButton("Back", (d, w) -> back.run())
                    .setOnCancelListener(d -> back.run())
                    .show();
            return;
        }
        Map<String, List<Entry>> groups = new LinkedHashMap<>();
        entries.sort((a, b) -> Long.compare(b.time, a.time));
        for (Entry e : entries) {
            groups.computeIfAbsent(e.id, k -> new ArrayList<>()).add(e);
        }
        // Rows: 0 is everything; then a character's row (entry null) and their saves.
        List<Entry> rowEntry = new ArrayList<>();
        List<String> rowGroup = new ArrayList<>();
        List<CharSequence> labels = new ArrayList<>();
        rowEntry.add(null);
        rowGroup.add(null);
        labels.add(bold("Everything (" + plural(entries.size()) + ")"));
        for (Map.Entry<String, List<Entry>> g : groups.entrySet()) {
            List<Entry> saves = g.getValue();
            String who = null;
            for (Entry e : saves) {
                if (e.who != null) {
                    who = e.who;
                    break;
                }
            }
            String when = new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(saves.get(0).time));
            rowEntry.add(null);
            rowGroup.add(g.getKey());
            labels.add(bold((who != null ? who : "Unnamed character (" + g.getKey() + ")")
                    + " - " + plural(saves.size()) + ", last " + when));
            for (Entry e : saves) {
                rowEntry.add(e);
                rowGroup.add(g.getKey());
                labels.add(indented(e.what));
            }
        }
        boolean[] checked = new boolean[labels.size()];
        AlertDialog[] dialog = new AlertDialog[1];
        Runnable update = () -> {
            int ticked = 0;
            for (int i = 0; i < checked.length; i++) {
                if (rowEntry.get(i) != null && checked[i]) {
                    ticked++;
                }
            }
            Button p = dialog[0].getButton(DialogInterface.BUTTON_POSITIVE);
            p.setText(ticked == 0 ? positive : positive + " " + ticked);
            p.setEnabled(ticked > 0);
            if (neutral != null) {
                Button n = dialog[0].getButton(DialogInterface.BUTTON_NEUTRAL);
                n.setText(ticked == 0 ? neutral : neutral + " (" + ticked + ")");
                n.setEnabled(ticked > 0);
            }
        };
        AlertDialog.Builder b = builder().setTitle(title)
                .setMultiChoiceItems(labels.toArray(new CharSequence[0]), checked, (d, which, on) -> {
                    // The list keeps `checked` as its own: it must say what every row shows.
                    String group = rowGroup.get(which);
                    for (int i = 0; i < checked.length; i++) {
                        if (which == 0 || (rowEntry.get(which) == null && group.equals(rowGroup.get(i)))) {
                            checked[i] = on;
                        }
                    }
                    checked[which] = on;
                    boolean all = true;
                    for (int i = 1; i < checked.length; i++) {
                        if (rowEntry.get(i) == null) {
                            boolean every = true;
                            for (int k = i + 1; k < checked.length && rowEntry.get(k) != null; k++) {
                                every &= checked[k];
                            }
                            checked[i] = every;
                            all &= every;
                        }
                    }
                    checked[0] = all;
                    ListView list = dialog[0].getListView();
                    for (int i = 0; i < checked.length; i++) {
                        list.setItemChecked(i, checked[i]);
                    }
                    update.run();
                })
                .setPositiveButton(positive, (d, w) -> {
                    List<Entry> chosen = new ArrayList<>();
                    List<String> whole = new ArrayList<>();
                    for (int i = 1; i < checked.length; i++) {
                        if (!checked[i]) {
                            continue;
                        }
                        if (rowEntry.get(i) != null) {
                            chosen.add(rowEntry.get(i));
                        } else {
                            whole.add(rowGroup.get(i));
                        }
                    }
                    onPositive.run(chosen, whole);
                })
                .setNegativeButton("Back", (d, w) -> back.run())
                .setOnCancelListener(d -> back.run());
        if (neutral != null) {
            b.setNeutralButton(neutral, (d, w) -> {
                List<Entry> chosen = new ArrayList<>();
                for (int i = 1; i < checked.length; i++) {
                    if (checked[i] && rowEntry.get(i) != null) {
                        chosen.add(rowEntry.get(i));
                    }
                }
                onNeutral.run(chosen, new ArrayList<>());
            });
        }
        dialog[0] = b.show();
        update.run();
    }

    private static CharSequence bold(String text) {
        SpannableString s = new SpannableString(text);
        s.setSpan(new StyleSpan(Typeface.BOLD), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /** A save's row, set in under its character's (a wrapped line too). */
    private CharSequence indented(String text) {
        int margin = Math.round(32 * activity.getResources().getDisplayMetrics().density);
        SpannableString s = new SpannableString(text);
        s.setSpan(new LeadingMarginSpan.Standard(margin), 0, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
