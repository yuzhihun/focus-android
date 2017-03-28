package org.mozilla.focus.webkit.matcher;

import android.content.Context;
import android.support.annotation.RawRes;
import android.util.JsonReader;

import org.mozilla.focus.R;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A focus-iOS inspired matcher: it uses a list of regexes for blocking (as opposed to
 * the original Android trie based approach). This version was implemented to verify that the trie-based
 * version does in fact exhibit identical behaviour to the iOS version.
 *
 * I have a feeling that this version is slower than the trie based version, but measurements have
 * not yet been produced.
 */
public class UrlMatcher2 {

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

    final ArrayList<BlockRule> blockList = new ArrayList<>();

    public UrlMatcher2(final Context context) throws IOException {
        final @RawRes int[] files = new int[] {
                R.raw.disconnect_advertising,
                R.raw.disconnect_analytics,
                R.raw.disconnect_content,
                R.raw.disconnect_social
        };

        for (int listFile : files) {
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
    }

    private static final String[] WEBFONT_EXTENSIONS = new String[]{
            ".woff2",
            ".woff",
            ".eot",
            ".ttf",
            ".otf"
    };

    // TODO: hook this up to prefs
    final boolean blockWebfonts = false;

    public boolean matches(final String resourceURLString, final String pageURLString) {
        final String resourceHost;
        final String documentHost;

        try {
            resourceHost = new URL(resourceURLString).getHost();
            if (!pageURLString.startsWith("data:")) {
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
            if (rule.regex.matcher(resourceHost).matches()) {
                // On iOS, we test if rule.loadType == thirdParty. However that is true for _all_
                // blocklist entries (except for fonts, but we handle those separately on Android).
                if (rule.regex.matcher(documentHost).matches()) {
                    // TODO: we need to do this in the original UrlMatcher
                    continue;
                }

                if (rule.domainExceptions.size() == 0) {
                    return true;
                }

                for (final Pattern exceptionPattern : rule.domainExceptions) {
                    if (exceptionPattern.matcher(documentHost).matches()) {
                        continue;
                    }
                }

                return true;
            }
        }

        return false;
    }
}
