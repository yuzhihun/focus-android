/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.utils;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class IOUtils {
    public static void safeClose(Closeable stream) {
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException ignored) { }
    }

    public static JSONObject readAsset(Context context, String fileName) throws IOException {
        InputStream stream = null;
        BufferedReader reader = null;

        try {
            stream = context.getAssets().open(fileName);
            reader = new BufferedReader(new InputStreamReader(stream));

            final StringBuilder builder = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }

            return new JSONObject(builder.toString());
        } catch (JSONException e) {
            throw new AssertionError("Corrupt JSON asset (" + fileName + ")", e);
        } finally {
            IOUtils.safeClose(reader);
            IOUtils.safeClose(stream);
        }
    }
}
