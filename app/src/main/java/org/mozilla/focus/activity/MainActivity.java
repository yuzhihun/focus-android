/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AppCompatActivity;
import android.util.AttributeSet;
import android.view.View;
import android.view.WindowManager;

import org.mozilla.focus.R;
import org.mozilla.focus.fragment.BrowserFragment;
import org.mozilla.focus.fragment.FirstrunFragment;
import org.mozilla.focus.fragment.HomeFragment;
import org.mozilla.focus.fragment.UrlInputFragment;
import org.mozilla.focus.telemetry.TelemetryWrapper;
import org.mozilla.focus.utils.Settings;
import org.mozilla.focus.web.IWebView;
import org.mozilla.focus.web.WebViewProvider;

public class MainActivity extends AppCompatActivity {
    private String pendingUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Settings appSettings = new Settings(this);

        if (appSettings.shouldUseSecureMode()) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        }

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        setContentView(R.layout.activity_main);


        if (savedInstanceState == null) {
            WebViewProvider.performCleanup(this);

            if (Intent.ACTION_VIEW.equals(getIntent().getAction())) {
                final String url = getIntent().getDataString();

                if (appSettings.shouldShowFirstrun()) {
                    pendingUrl = url;
                    showFirstrun();
                } else {
                    showBrowserScreen(url);
                }
            } else {
                if (appSettings.shouldShowFirstrun()) {
                    showFirstrun();
                } else {
                    showHomeScreen();
                }
            }
        }

        WebViewProvider.preload(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        TelemetryWrapper.startSession();
    }

    @Override
    protected void onPause() {
        if (isFinishing()) {
            WebViewProvider.performCleanup(this);
        }

        super.onPause();

        TelemetryWrapper.stopSession();
    }

    @Override
    protected void onStop() {
        super.onStop();

        TelemetryWrapper.stopMainActivity();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            // We can't update our fragment right now because we need to wait until the activity is
            // resumed. So just remember this URL and load it in onResume().
            pendingUrl = intent.getDataString();
        }
    }

    @Override
    protected void onResumeFragments() {
        super.onResumeFragments();

        if (pendingUrl != null && !new Settings(this).shouldShowFirstrun()) {
            // We have received an URL in onNewIntent(). Let's load it now.
            // Unless we're trying to show the firstrun screen, in which case we leave it pending until
            // firstrun is dismissed.
            showBrowserScreen(pendingUrl);
            pendingUrl = null;
        }
    }

    private void showHomeScreen() {
        // We add the home fragment to the layout if it doesn't exist yet. I tried adding the fragment
        // to the layout directly but then I wasn't able to remove it later. It was still visible but
        // without an activity attached. So let's do it manually.
        final FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.findFragmentByTag(HomeFragment.FRAGMENT_TAG) == null) {
            fragmentManager
                    .beginTransaction()
                    .replace(R.id.container, HomeFragment.create(), HomeFragment.FRAGMENT_TAG)
                    .commit();
        }
    }

    private void showFirstrun() {
        final FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.findFragmentByTag(FirstrunFragment.FRAGMENT_TAG) == null) {
            fragmentManager
                    .beginTransaction()
                    .replace(R.id.container, FirstrunFragment.create(), FirstrunFragment.FRAGMENT_TAG)
                    .commit();
        }
    }

    private void showBrowserScreen(String url) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.container,
                        BrowserFragment.create(url), BrowserFragment.FRAGMENT_TAG)
                .commit();

        TelemetryWrapper.browseIntentEvent();
    }

    @Override
    public View onCreateView(String name, Context context, AttributeSet attrs) {
        if (name.equals(IWebView.class.getName())) {
            return WebViewProvider.create(this, attrs);
        }

        return super.onCreateView(name, context, attrs);
    }

    @Override
    public void onBackPressed() {
        final FragmentManager fragmentManager = getSupportFragmentManager();

        final UrlInputFragment urlInputFragment = (UrlInputFragment) fragmentManager.findFragmentByTag(UrlInputFragment.FRAGMENT_TAG);
        if (urlInputFragment != null && urlInputFragment.isVisible()) {
            if (urlInputFragment.onBackPressed()) {
                // The URL input fragment has handled the back press. It does its own animations so
                // we do not try to remove it from outside.
                return;
            }
        }

        final BrowserFragment browserFragment = (BrowserFragment) fragmentManager.findFragmentByTag(BrowserFragment.FRAGMENT_TAG);
        if (browserFragment != null && browserFragment.isVisible()) {
            if (browserFragment.onBackPressed()) {
                // The Browser fragment handles back presses on its own because it might just go back
                // in the browsing history.
                return;
            }
        }

        super.onBackPressed();
    }

    public void firstrunFinished() {
        if (pendingUrl != null) {
            // We have received an URL in onNewIntent(). Let's load it now.
            showBrowserScreen(pendingUrl);
            pendingUrl = null;
        } else {
            showHomeScreen();
        }
    }
}
