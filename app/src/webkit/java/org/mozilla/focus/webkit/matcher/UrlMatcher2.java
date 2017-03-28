package org.mozilla.focus.webkit.matcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.util.JsonReader;
import android.util.Log;

import org.mozilla.focus.R;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * A focus-iOS inspired matcher: it uses a list of regexes for blocking (as opposed to
 * the original Android trie based approach). This version was implemented to verify that the trie-based
 * version does in fact exhibit identical behaviour to the iOS version.
 *
 * I have a feeling that this version is slower than the trie based version, but measurements have
 * not yet been produced.
 *
 * We could extract common code from UrlMatcher and this class, but in the long run we should
 * remove one of the two UrlMatcher's anyway - so having copies of (the primarily pref-handling)
 * is probably better for now.
 */
public class UrlMatcher2 implements SharedPreferences.OnSharedPreferenceChangeListener{
    private static final int ID_WEBFONTS = -1;

    private static final String LOGTAG = "URLMATCHER2";

    private static Map<String, Integer> loadDefaultPrefMap(final Context context) {
        Map<String, Integer> tempMap = new HashMap<>(5);

        tempMap.put(context.getString(R.string.pref_key_privacy_block_ads), R.raw.disconnect_advertising);
        tempMap.put(context.getString(R.string.pref_key_privacy_block_analytics), R.raw.disconnect_analytics);
        tempMap.put(context.getString(R.string.pref_key_privacy_block_social), R.raw.disconnect_social);
        tempMap.put(context.getString(R.string.pref_key_privacy_block_other), R.raw.disconnect_content);

        // This is a "fake" category - webfont handling is independent of the blocklists
        tempMap.put(context.getString(R.string.pref_key_performance_block_webfonts), ID_WEBFONTS);

        return Collections.unmodifiableMap(tempMap);
    }

    /**
     * Map of pref to blocking category (preference key -> file ID).
     */
    private final Map<String, Integer> categoryPrefMap;

    private final Context context;

    private boolean blockWebfonts = true;

    private final AtomicBoolean pendingLoads = new AtomicBoolean();

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String prefName) {

        // Only do something for the prefs we care about:
        if (categoryPrefMap.containsKey(prefName)) {
            if (prefName.equals(context.getString(R.string.pref_key_performance_block_webfonts))) {
                blockWebfonts = sharedPreferences.getBoolean(prefName, true);
            } else {
                Log.d(LOGTAG, "Prefs changed, attempting to lock");

                if (pendingLoads.getAndSet(true)) {
                    Log.d(LOGTAG, "Prefs changed, but load is already pending");
                    return;
                }

                Log.d(LOGTAG, "Prefs changed, lock successful");

                new AsyncTask<Void, Void, Void>() {
                    @Override
                    protected Void doInBackground(Void... params) {
                        // pendingLoads is unlocked as soon as we start reloadMatcher, since
                        // at that point we'll need a reload if settings change. I.e pendingLoads()
                        // is only locked if there is a completely fresh load scheduled to happen
                        // (and we use synchronization to not check for matches while a load is ongoing).
                        Log.d(LOGTAG, "Attempting to reload");
                        reloadMatcher();
                        return null;
                    }
                }.execute();
            }
        }
    }

    private static class BlockRule {
        private final Pattern regex;
        private final List<Pattern> domainExceptions;

        public BlockRule(final Pattern regex, final List<Pattern> domainExceptions) {
            this.regex = regex;
            this.domainExceptions = domainExceptions;
        }
    }

    private static BlockRule extractSiteEntry(final JsonReader reader) throws IOException {
        reader.beginObject();

        String filter = null;
        final LinkedList<Pattern> unlessList = new LinkedList<>();

        while (reader.hasNext()) {
            final String name = reader.nextName();

            if ("url-filter".equals(name)) {
                filter = reader.nextString();
            } else if ("load-type".equals(name)) {
                // Only used by iOS, is hardcoded to "third-party"
                // Is a list of strings
                reader.skipValue();
            } else if ("unless-domain".equals(name)) {
                // Contains a list of strings
                reader.beginArray();
                while (reader.hasNext()) {
                    final String unless = reader.nextString();
                    String regex = unless + "$";
                    if (regex.charAt(0) == '*') {
                        regex = '.' + regex;
                    }
                    regex.replace(".", "\\.");

                    final Pattern pattern = Pattern.compile(regex);

                    unlessList.push(pattern);
                }
                reader.endArray();
            } else {
                // There can be a resource-type (e.g. ="font"), but that isn't used in our disconnect lists
                // (and font blocking is done separately).
                throw new IllegalStateException("Unexpected tag in site object: " + name);
            }
        }

        if (filter == null) {
            throw new IllegalStateException("No site URL found for current site");
        }

        reader.endObject();

        final Pattern pattern = Pattern.compile(filter);
        return new BlockRule(pattern, unlessList);
    }

    ArrayList<BlockRule> blockList;

    public UrlMatcher2(final Context context) {
        this.context = context.getApplicationContext();

        categoryPrefMap = loadDefaultPrefMap(context);

        reloadMatcher();

        PreferenceManager.getDefaultSharedPreferences(context).registerOnSharedPreferenceChangeListener(this);
    }

    private synchronized void reloadMatcher() {
        Log.d(LOGTAG, "Performing reload");

        // Any pref changes after this time will require a new load
        pendingLoads.set(false);

        final ArrayList<Integer> enabledFiles = new ArrayList<>();

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        for (final Map.Entry<String, Integer> entry : categoryPrefMap.entrySet()) {
            final boolean prefEnabled = prefs.getBoolean(entry.getKey(), true);
            final int fileID = entry.getValue();

            if (fileID == ID_WEBFONTS) {
                blockWebfonts = prefEnabled;
            } else if (prefEnabled) {
                enabledFiles.add(fileID);
            }
        }

        // Clear all existing loaded data
        blockList = new ArrayList<>();

        try {
            for (int listFile : enabledFiles) {
                InputStream inputStream = context.getResources().openRawResource(listFile);
                JsonReader jsonReader = new JsonReader(new InputStreamReader(inputStream));

                jsonReader.beginArray();


                while (jsonReader.hasNext()) {
                    jsonReader.beginObject();

                    while (jsonReader.hasNext()) {
                        final String name = jsonReader.nextName();

                        if ("action".equals(name)) {
                            // These are all set to "block" (this is used by iOS's blocking extension)
                            jsonReader.skipValue();
                        } else if ("trigger".equals(name)) {
                            blockList.add(extractSiteEntry(jsonReader));
                        } else {
                            throw new IllegalStateException("Unexpected tag: " + name);
                        }
                    }

                    jsonReader.endObject();
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load blocklist data - this shouldn't ever happen");
        }
        Log.d(LOGTAG, "reload complete");
    }

    private static final String[] WEBFONT_EXTENSIONS = new String[]{
            ".woff2",
            ".woff",
            ".eot",
            ".ttf",
            ".otf"
    };

    public boolean matches(final String resourceURLString, final String pageURLString) {
        // Waits until lock is released:

        while (pendingLoads.get()) {
            Log.d(LOGTAG, "matches() is waiting for loads to complete");
            // We still have a small window between when pendingLoads() is set, and the background
            // thread kicking in, can we fix that somehow?
            synchronized (this) {
                // Wait until no loading is ongoing, then check if we still have another load scheduled
            }
        }

        Log.d(LOGTAG, "matches() is running");

        // We still block on ongoing loads here thanks to matchesInternal being synchronized
        return matchesInternal(resourceURLString, pageURLString);
    }

    private synchronized boolean matchesInternal(final String resourceURLString, final String pageURLString) {
        final String documentHost;

        try {
            if (pageURLString.startsWith("http:") || pageURLString.startsWith("https:")) {
                documentHost = new URL(pageURLString).getHost();
            } else {
                documentHost = pageURLString;
            }
        } catch (MalformedURLException e) {
            // In reality this should never happen - unless webkit were to pass us an invalid URL.
            // If we ever hit this in the wild, we might want to think our approach...
            throw new IllegalArgumentException("Unable to handle malformed resource URL");
        }

        if (blockWebfonts) {
            for (final String extension : WEBFONT_EXTENSIONS) {
                if (resourceURLString.endsWith(extension)) {
                    return true;
                }
            }
        }

        for (final BlockRule rule : blockList) {
            if (rule.regex.matcher(resourceURLString).find()) {
                // On iOS, we test if rule.loadType == thirdParty. However that is true for _all_
                // blocklist entries (except for fonts, but we handle those separately on Android).
                if (rule.regex.matcher(documentHost).find()) {
                    // TODO: we need to do this in the original UrlMatcher
                    continue;
                }

                if (rule.domainExceptions.size() == 0) {
                    return true;
                }

                for (final Pattern exceptionPattern : rule.domainExceptions) {
                    if (exceptionPattern.matcher(documentHost).matches()) {
                        return false;
                    }
                }

                return true;
            }
        }

        return false;
    }
}
