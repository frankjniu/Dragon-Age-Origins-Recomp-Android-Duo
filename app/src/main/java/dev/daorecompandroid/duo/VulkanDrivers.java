package dev.daorecompandroid.duo;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * PLAN 9.23 (the owner, 2026-11-16; GitHub issue 4): Vulkan driver packages the player
 * imports - libadrenotools' .zip packages (a meta.json naming the driver's library, and
 * the libraries), such as Mesa's Turnip builds or Qualcomm drivers taken from other
 * devices - and the choice between them and the system's driver (Options -> Visual ->
 * Experimental). Each is unpacked into files/drivers/&lt;package&gt;/ (internal storage:
 * the linker loads nothing from shared storage); files/vulkan-driver names the one chosen,
 * which the game's runtime loads as it starts (warden_vulkan_loader.cc) - the Vulkan
 * device is made as the app opens, so a choice takes effect at the app's next start.
 *
 * <p>The start's guard: a start with an imported driver writes files/vulkan-driver-trying,
 * removed once the launcher is up, written again as the game launches and removed by the
 * runtime after the game's 300th frame. Found at a start, the last one did not get there
 * (the driver ended the process) - the system's driver is chosen again and the row says so.
 */
final class VulkanDrivers {

    private static final String TAG = "Warden";

    static final String SYSTEM = "system";
    static final String CHOICE = "vulkan-driver";
    private static final String TRYING = "vulkan-driver-trying";
    private static final String STATUS = "vulkan-driver-status";
    /** A package is libraries of a few tens of MB; more is not one. */
    private static final long MAX_BYTES = 256L << 20;

    static final int REQUEST_DRIVER_ZIP = 51;

    /** One imported package, from its meta.json. */
    static final class Package {
        String id;
        String name = "";
        String vendor = "";
        String driverVersion = "";
        String author = "";
        String description = "";
        String library = "";
        int minApi;
    }

    /** The driver this process's game runs on: SYSTEM, or the package loaded. */
    static String loadedAtStart = SYSTEM;
    /** What the last import, removal or start had to say, shown under the rows; or "". */
    static String note = "";

    private VulkanDrivers() {
    }

    static File folder(File dir) {
        return new File(dir, "drivers");
    }

    /** The packages imported, by name. */
    static List<Package> list(File dir) {
        List<Package> packages = new ArrayList<>();
        File[] folders = folder(dir).listFiles();
        if (folders == null) {
            return packages;
        }
        Arrays.sort(folders);
        for (File folder : folders) {
            if (!folder.isDirectory() || folder.getName().startsWith(".")) {
                continue;
            }
            Package p = readMeta(new File(folder, "meta.json"));
            if (p != null && new File(folder, p.library).isFile()) {
                p.id = folder.getName();
                packages.add(p);
            }
        }
        return packages;
    }

    static Package find(File dir, String id) {
        for (Package p : list(dir)) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    private static Package readMeta(File file) {
        try {
            return parseMeta(new String(readAll(new java.io.FileInputStream(file)),
                    StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A package's meta.json; null when it is not one (no name, no plain library name). */
    private static Package parseMeta(String text) {
        try {
            JSONObject meta = new JSONObject(text);
            Package p = new Package();
            p.name = meta.optString("name", "").trim();
            p.vendor = meta.optString("vendor", "").trim();
            p.driverVersion = meta.optString("driverVersion", "").trim();
            p.author = meta.optString("author", "").trim();
            p.description = meta.optString("description", "").trim();
            p.library = meta.optString("libraryName", "").trim();
            p.minApi = meta.optInt("minApi", 0);
            if (p.name.isEmpty() || !plain(p.library) || !p.library.endsWith(".so")) {
                return null;
            }
            return p;
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    private static boolean plain(String name) {
        return !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0;
    }

    /** The row's value: the package's name without its parenthesis, kept short. */
    static String label(Package p) {
        String label = p.name.replaceAll("\\s*\\(.*\\)\\s*$", "").trim();
        if (label.isEmpty()) {
            label = p.name;
        }
        return label.length() > 30 ? label.substring(0, 29) + "…" : label;
    }

    /** The row's sentence for a package (the panel shows three lines). */
    static String detail(Package p) {
        StringBuilder text = new StringBuilder(p.name);
        String about = (p.vendor.isEmpty() ? "" : p.vendor)
                + (p.vendor.isEmpty() || p.driverVersion.isEmpty() ? "" : ", ")
                + p.driverVersion;
        if (!about.isEmpty()) {
            text.append(" (").append(about).append(")");
        }
        text.append(".");
        if (p.minApi > Build.VERSION.SDK_INT) {
            text.append(" Made for Android API ").append(p.minApi).append("+: it may not load.");
        }
        text.append(" Experimental - the game is tested on the system's driver; a start that"
                + " fails brings it back.");
        return text.toString();
    }

    static final String SYSTEM_DETAIL = "The handheld's own Vulkan driver, as Android loads it -"
            + " the one the game is tested with.";

    /**
     * The Vulkan driver row's values from the packages there are (Options -> Experimental),
     * before any read of it: Options.read checks the file against them.
     */
    static void refresh(File dir) {
        List<Package> packages = list(dir);
        String[] values = new String[packages.size() + 1];
        String[] labels = new String[values.length];
        String[] details = new String[values.length];
        values[0] = SYSTEM;
        labels[0] = "System";
        details[0] = SYSTEM_DETAIL;
        for (int i = 0; i < packages.size(); i++) {
            values[i + 1] = packages.get(i).id;
            labels[i + 1] = label(packages.get(i));
            details[i + 1] = detail(packages.get(i));
        }
        Options.Option option = Options.byFile(CHOICE);
        option.values = values;
        option.labels = labels;
        option.details = details;
    }

    static String chosen(File dir) {
        return Options.read(dir, Options.byFile(CHOICE));
    }

    private static void choose(File dir, String id) {
        Options.write(dir, Options.byFile(CHOICE), id);
    }

    private static String nameOf(File dir, String id) {
        Package p = find(dir, id);
        return p != null ? p.name : id;
    }

    /** The app opens: the last start's guard read, this one's written. */
    static void beforeStartup(File dir) {
        refresh(dir);
        File trying = new File(dir, TRYING);
        if (trying.isFile()) {
            String id = readLine(trying);
            trying.delete();
            if (!id.isEmpty() && id.equals(chosen(dir))) {
                choose(dir, SYSTEM);
                note = nameOf(dir, id) + ": the last start with it did not reach the game, so"
                        + " the system's driver is back. Choose it again to try once more.";
                Log.w(TAG, "vulkan driver: the last start with " + id + " did not reach the game"
                        + " - the system's driver chosen again");
            }
        }
        String id = chosen(dir);
        if (!id.equals(SYSTEM)) {
            writeLine(trying, id);
        }
    }

    /**
     * The runtime has made its device (or failed to): what it loaded, from its status file.
     * Straight into the game (the test scripts' --ez boot), the guard stays until the game's
     * frames; in the launcher it is done.
     */
    static void afterStartup(File dir, boolean started, boolean straightIntoGame) {
        String[] status = readLines(new File(dir, STATUS));
        String first = status.length > 0 ? status[0] : SYSTEM;
        String why = status.length > 1 ? status[1] : "";
        File trying = new File(dir, TRYING);
        loadedAtStart = SYSTEM;
        if (first.startsWith("loaded ")) {
            loadedAtStart = first.substring("loaded ".length());
        } else if (first.startsWith("failed ")) {
            String id = first.substring("failed ".length());
            trying.delete();
            if (id.equals(chosen(dir))) {
                choose(dir, SYSTEM);
            }
            note = nameOf(dir, id) + " could not be loaded (" + why + "): the system's driver"
                    + " is in use, and chosen again.";
            Log.w(TAG, "vulkan driver: " + id + " could not be loaded - " + why);
            return;
        }
        if (!started && !loadedAtStart.equals(SYSTEM)) {
            trying.delete();
            if (loadedAtStart.equals(chosen(dir))) {
                choose(dir, SYSTEM);
            }
            note = nameOf(dir, loadedAtStart) + " made no Vulkan device the game can use: the"
                    + " system's driver is chosen again - open the app again to play.";
            Log.w(TAG, "vulkan driver: no device on " + loadedAtStart
                    + " - the system's driver chosen again");
            return;
        }
        if (!loadedAtStart.equals(SYSTEM)) {
            Log.i(TAG, "vulkan driver: the game's device is on the package " + loadedAtStart);
            if (!straightIntoGame) {
                trying.delete();
            }
        }
    }

    /** The game launches from the launcher: its guard again, until its frames are shown. */
    static void armForLaunch(File dir) {
        if (!loadedAtStart.equals(SYSTEM)) {
            writeLine(new File(dir, TRYING), loadedAtStart);
        }
    }

    static Intent pickIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Any file: a package's .adpkg.zip is not always typed as a zip.
        intent.setType("*/*");
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents", "primary:Download"));
        return intent;
    }

    /**
     * Unpacks a package (off the main thread): its meta.json and its libraries, nothing
     * else and no paths, into files/drivers/&lt;package&gt;/, then chooses it. Returns what
     * to say.
     */
    static String importZip(Context context, Uri uri) {
        File dir = context.getFilesDir();
        String fileName = displayName(context, uri);
        File drivers = folder(dir);
        drivers.mkdirs();
        File temp = new File(drivers, ".import");
        deleteTree(temp);
        temp.mkdirs();
        String result;
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) {
                return "The file could not be read.";
            }
            result = unpack(raw, temp);
        } catch (IOException e) {
            Log.e(TAG, "vulkan driver: import of " + fileName + " failed", e);
            result = "The file could not be read as a .zip (" + e.getMessage() + ").";
        }
        if (result != null) {
            deleteTree(temp);
            note = fileName + ": " + result;
            Log.w(TAG, "vulkan driver: " + note);
            return note;
        }
        Package p = readMeta(new File(temp, "meta.json"));
        String id = idFor(p.name);
        File target = new File(drivers, id);
        deleteTree(target);
        if (!temp.renameTo(target)) {
            deleteTree(temp);
            note = fileName + ": it could not be put in the app's storage.";
            return note;
        }
        refresh(dir);
        choose(dir, id);
        note = "Imported " + label(p) + " and chosen: from the app's next start.";
        Log.i(TAG, "vulkan driver: imported " + fileName + " as " + id + " - " + p.name
                + ", " + p.library);
        return note;
    }

    /** Null when the package is whole in {@code into}, else why it is not one. */
    private static String unpack(InputStream raw, File into) throws IOException {
        long total = 0;
        String prefix = null;
        List<String> libraries = new ArrayList<>();
        byte[] meta = null;
        try (ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            byte[] buffer = new byte[1 << 16];
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String path = entry.getName().replace('\\', '/');
                int slash = path.lastIndexOf('/');
                String folder = slash < 0 ? "" : path.substring(0, slash + 1);
                String name = path.substring(slash + 1);
                // The package's files are at its top, or in one folder of it.
                if (folder.contains("..") || folder.indexOf('/') != folder.lastIndexOf('/')) {
                    continue;
                }
                boolean wanted = name.equals("meta.json") || name.endsWith(".so");
                if (!wanted || !plain(name)) {
                    continue;
                }
                if (prefix == null) {
                    prefix = folder;
                } else if (!prefix.equals(folder)) {
                    return "its files are in more than one folder - not a driver package.";
                }
                ByteArrayOutputStream metaBytes = name.equals("meta.json")
                        ? new ByteArrayOutputStream() : null;
                try (OutputStream out = metaBytes != null ? metaBytes
                        : new FileOutputStream(new File(into, name))) {
                    int n;
                    while ((n = zip.read(buffer)) > 0) {
                        total += n;
                        if (total > MAX_BYTES) {
                            return "more than 256 MB - not a driver package.";
                        }
                        out.write(buffer, 0, n);
                    }
                }
                if (metaBytes != null) {
                    meta = metaBytes.toByteArray();
                    try (FileOutputStream out = new FileOutputStream(new File(into, name))) {
                        out.write(meta);
                    }
                } else {
                    libraries.add(name);
                }
            }
        }
        if (meta == null) {
            return "no meta.json - not a driver package (Adrenotools' .zip packages have one).";
        }
        Package p = parseMeta(new String(meta, StandardCharsets.UTF_8));
        if (p == null) {
            return "its meta.json names no driver library.";
        }
        if (!libraries.contains(p.library)) {
            return "its meta.json names " + p.library + ", which is not in it.";
        }
        for (String library : libraries) {
            File file = new File(into, library);
            file.setReadable(true, true);
            file.setWritable(false, false);
        }
        return null;
    }

    /** A folder name for a package: its name in lower case, letters, digits and dashes. */
    static String idFor(String name) {
        String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (id.length() > 48) {
            id = id.substring(0, 48).replaceAll("-+$", "");
        }
        return id.isEmpty() || id.equals(SYSTEM) ? "driver" : id;
    }

    /** The Remove row: the chosen package deleted, the system's driver chosen. */
    static String removeChosen(File dir) {
        String id = chosen(dir);
        if (id.equals(SYSTEM)) {
            note = "Nothing to remove: the system's driver is chosen. Choose a package in the"
                    + " Vulkan driver row first.";
            return note;
        }
        String name = nameOf(dir, id);
        deleteTree(new File(folder(dir), id));
        // Its own pipeline cache (Xenia's, per driver) goes with it.
        File[] caches = new File(dir, "pipeline-storage-v1").listFiles();
        if (caches != null) {
            for (File sub : caches) {
                File[] files = sub.isDirectory() ? sub.listFiles() : new File[] {sub};
                if (files == null) {
                    continue;
                }
                for (File file : files) {
                    if (file.getName().endsWith("-" + id + ".vk.bin")) {
                        file.delete();
                    }
                }
            }
        }
        // PLAN 9.24: frame generation's pipelines and its precision choice for the driver.
        new File(dir, "lsfg-pipelines16-" + id + ".bin").delete();
        new File(dir, "lsfg-pipelines32-" + id + ".bin").delete();
        new File(dir, "frame-generation-precision-" + id + ".txt").delete();
        choose(dir, SYSTEM);
        refresh(dir);
        note = "Removed " + name + ". The system's driver is chosen"
                + (loadedAtStart.equals(id) ? " - from the app's next start." : ".");
        Log.i(TAG, "vulkan driver: removed " + id);
        return note;
    }

    static String removeDetail(File dir) {
        String id = chosen(dir);
        if (id.equals(SYSTEM)) {
            return "Removes the package chosen in the Vulkan driver row from the app's storage."
                    + " The system's driver is chosen now: nothing to remove.";
        }
        return "Removes " + nameOf(dir, id) + " from the app's storage, and chooses the"
                + " system's driver.";
    }

    static final String IMPORT_DETAIL = "A Vulkan driver package for Adreno GPUs: a .zip with a"
            + " meta.json, as Adrenotools takes (Mesa's Turnip, Qualcomm's from other devices)."
            + " Copied into the app and chosen in the row above.";

    private static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (RuntimeException e) {
            // The name is only for the message.
        }
        return "The file";
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = stream.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String readLine(File file) {
        String[] lines = readLines(file);
        return lines.length > 0 ? lines[0] : "";
    }

    private static String[] readLines(File file) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = in.readLine()) != null) {
                lines.add(line.trim());
            }
        } catch (IOException e) {
            // None.
        }
        return lines.toArray(new String[0]);
    }

    private static void writeLine(File file, String text) {
        try (FileWriter out = new FileWriter(file)) {
            out.write(text + "\n");
        } catch (IOException e) {
            Log.e(TAG, "vulkan driver: cannot write " + file, e);
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
}
