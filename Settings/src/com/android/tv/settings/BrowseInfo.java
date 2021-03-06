/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tv.settings;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.Intent.ShortcutIconResource;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceActivity;
import android.support.v17.leanback.widget.ArrayObjectAdapter;
import android.support.v17.leanback.widget.HeaderItem;
import android.support.v17.leanback.widget.ObjectAdapter;
import android.support.v17.leanback.widget.ListRow;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.util.Xml;

import com.android.internal.util.XmlUtils;
import com.android.tv.settings.accessories.AccessoryUtils;
import com.android.tv.settings.accessories.BluetoothAccessoryActivity;
import com.android.tv.settings.accessories.BluetoothConnectionsManager;
import com.android.tv.settings.accounts.AccountImageUriGetter;
import com.android.tv.settings.accounts.AccountSettingsActivity;
import com.android.tv.settings.accounts.AddAccountWithTypeActivity;
import com.android.tv.settings.accounts.AuthenticatorHelper;
import com.android.tv.settings.connectivity.ConnectivityStatusIconUriGetter;
import com.android.tv.settings.connectivity.ConnectivityStatusTextGetter;
import com.android.tv.settings.connectivity.WifiNetworksActivity;
import com.android.tv.settings.device.sound.SoundActivity;
import com.android.tv.settings.users.RestrictedProfileActivity;
import com.android.tv.settings.util.UriUtils;
import com.android.tv.settings.util.AccountImageHelper;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;

/**
 * Gets the list of browse headers and browse items.
 */
public class BrowseInfo extends BrowseInfoBase {

    private static final String TAG = "CanvasSettings.BrowseInfo";
    private static final boolean DEBUG = false;

    public static final String EXTRA_ACCESSORY_ADDRESS = "accessory_address";
    public static final String EXTRA_ACCESSORY_NAME = "accessory_name";
    public static final String EXTRA_ACCESSORY_ICON_ID = "accessory_icon_res";

    private static final String ACCOUNT_TYPE_GOOGLE = "com.google";

    private static final String ETHERNET_PREFERENCE_KEY = "ethernet";

    interface XmlReaderListener {
        void handleRequestedNode(Context context, XmlResourceParser parser, AttributeSet attrs)
                throws org.xmlpull.v1.XmlPullParserException, IOException;
    }

    static class SoundActivityImageUriGetter implements MenuItem.UriGetter {

        private final Context mContext;

        SoundActivityImageUriGetter(Context context) {
            mContext = context;
        }

        @Override
        public String getUri() {
            return UriUtils.getAndroidResourceUri(mContext.getResources(),
                    SoundActivity.getIconResource(mContext.getContentResolver()));
        }
    }

    static class XmlReader {

        private final Context mContext;
        private final int mXmlResource;
        private final String mRootNodeName;
        private final String mNodeNameRequested;
        private final XmlReaderListener mListener;

        XmlReader(Context context, int xmlResource, String rootNodeName, String nodeNameRequested,
                XmlReaderListener listener) {
            mContext = context;
            mXmlResource = xmlResource;
            mRootNodeName = rootNodeName;
            mNodeNameRequested = nodeNameRequested;
            mListener = listener;
        }

        void read() {
            XmlResourceParser parser = null;
            try {
                parser = mContext.getResources().getXml(mXmlResource);
                AttributeSet attrs = Xml.asAttributeSet(parser);

                int type;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                        && type != XmlPullParser.START_TAG) {
                    // Parse next until start tag is found
                }

                String nodeName = parser.getName();
                if (!mRootNodeName.equals(nodeName)) {
                    throw new RuntimeException("XML document must start with <" + mRootNodeName
                            + "> tag; found" + nodeName + " at " + parser.getPositionDescription());
                }

                Bundle curBundle = null;

                final int outerDepth = parser.getDepth();
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                        && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                    if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                        continue;
                    }

                    nodeName = parser.getName();
                    if (mNodeNameRequested.equals(nodeName)) {
                        mListener.handleRequestedNode(mContext, parser, attrs);
                    } else {
                        XmlUtils.skipCurrentTag(parser);
                    }
                }

            } catch (XmlPullParserException e) {
                throw new RuntimeException("Error parsing headers", e);
            } catch (IOException e) {
                throw new RuntimeException("Error parsing headers", e);
            } finally {
                if (parser != null)
                    parser.close();
            }
        }
    }

    private static final String PREF_KEY_ADD_ACCOUNT = "add_account";
    private static final String PREF_KEY_ADD_ACCESSORY = "add_accessory";
    private static final String PREF_KEY_WIFI = "network";
    private static final String PREF_KEY_DEVELOPER = "developer";
    private static final String PREF_KEY_INPUTS = "inputs";

    private final Context mContext;
    private final AuthenticatorHelper mAuthenticatorHelper;
    private int mNextItemId;
    private int mAccountHeaderId;
    private final BluetoothAdapter mBtAdapter;
    private final Object mGuard = new Object();
    private MenuItem mWifiItem = null;
    private ArrayObjectAdapter mWifiRow = null;
    private final Handler mHandler = new Handler();

    private PreferenceUtils mPreferenceUtils;
    private boolean mDeveloperEnabled;
    private boolean mInputSettingNeeded;

    BrowseInfo(Context context) {
        mContext = context;
        mAuthenticatorHelper = new AuthenticatorHelper();
        mAuthenticatorHelper.updateAuthDescriptions(context);
        mAuthenticatorHelper.onAccountsUpdated(context, null);
        mBtAdapter = BluetoothAdapter.getDefaultAdapter();
        mNextItemId = 0;
        mPreferenceUtils = new PreferenceUtils(context);
        mDeveloperEnabled = mPreferenceUtils.isDeveloperEnabled();
        mInputSettingNeeded = isInputSettingNeeded();
    }

    void init() {
        synchronized (mGuard) {
            mHeaderItems.clear();
            mRows.clear();
            int settingsXml = isRestricted() ? R.xml.restricted_main : R.xml.main;
            new XmlReader(mContext, settingsXml, "preference-headers", "header",
                    new HeaderXmlReaderListener()).read();
            updateAccessories(R.id.accessories);
        }
    }

    void checkForDeveloperOptionUpdate() {
        final boolean developerEnabled = mPreferenceUtils.isDeveloperEnabled();
        if (developerEnabled != mDeveloperEnabled) {
            mDeveloperEnabled = developerEnabled;
            init();
        }
    }

    private class HeaderXmlReaderListener implements XmlReaderListener {
        @Override
        public void handleRequestedNode(Context context, XmlResourceParser parser,
                AttributeSet attrs)
                throws XmlPullParserException, IOException {
            TypedArray sa = mContext.getResources().obtainAttributes(attrs,
                    com.android.internal.R.styleable.PreferenceHeader);
            final int headerId = sa.getResourceId(
                    com.android.internal.R.styleable.PreferenceHeader_id,
                    (int) PreferenceActivity.HEADER_ID_UNDEFINED);
            String title = getStringFromTypedArray(sa,
                    com.android.internal.R.styleable.PreferenceHeader_title);
            sa.recycle();
            sa = context.getResources().obtainAttributes(attrs, R.styleable.CanvasSettings);
            int preferenceRes = sa.getResourceId(R.styleable.CanvasSettings_preference, 0);
            sa.recycle();
            mHeaderItems.add(new HeaderItem(headerId, title));
            final ArrayObjectAdapter currentRow = new ArrayObjectAdapter();
            mRows.put(headerId, currentRow);
            if (headerId != R.id.accessories) {
                new XmlReader(context, preferenceRes, "PreferenceScreen", "Preference",
                        new PreferenceXmlReaderListener(headerId, currentRow)).read();
            }
        }
    }

    private boolean isRestricted() {
        return RestrictedProfileActivity.isRestrictedProfileInEffect(mContext);
    }

    private class PreferenceXmlReaderListener implements XmlReaderListener {

        private final int mHeaderId;
        private final ArrayObjectAdapter mRow;

        PreferenceXmlReaderListener(int headerId, ArrayObjectAdapter row) {
            mHeaderId = headerId;
            mRow = row;
        }

        @Override
        public void handleRequestedNode(Context context, XmlResourceParser parser,
                AttributeSet attrs) throws XmlPullParserException, IOException {
            TypedArray sa = context.getResources().obtainAttributes(attrs,
                    com.android.internal.R.styleable.Preference);

            String key = getStringFromTypedArray(sa,
                    com.android.internal.R.styleable.Preference_key);
            String title = getStringFromTypedArray(sa,
                    com.android.internal.R.styleable.Preference_title);
            int iconRes = sa.getResourceId(com.android.internal.R.styleable.Preference_icon,
                    R.drawable.settings_default_icon);
            sa.recycle();

            if (PREF_KEY_ADD_ACCOUNT.equals(key)) {
                mAccountHeaderId = mHeaderId;
                addAccounts(mRow);
            } else if ((!key.equals(PREF_KEY_DEVELOPER) || mDeveloperEnabled)
                    && (!key.equals(PREF_KEY_INPUTS) || mInputSettingNeeded)) {
                MenuItem.TextGetter descriptionGetter = getDescriptionTextGetterFromKey(key);
                MenuItem.UriGetter uriGetter = getIconUriGetterFromKey(key);
                MenuItem.Builder builder = new MenuItem.Builder().id(mNextItemId++).title(title)
                        .descriptionGetter(descriptionGetter)
                        .intent(getIntent(parser, attrs, mHeaderId));
                if(uriGetter == null) {
                    builder.imageResourceId(mContext, iconRes);
                } else {
                    builder.imageUriGetter(uriGetter);
                }
                if (key.equals(PREF_KEY_WIFI)) {
                    mWifiItem = builder.build();
                    mRow.add(mWifiItem);
                    mWifiRow = mRow;
                } else {
                    mRow.add(builder.build());
                }
            }
        }
    }

    void rebuildInfo() {
        init();
    }

    void updateAccounts() {
        synchronized (mGuard) {
            if (isRestricted()) {
                // We don't display the accounts in restricted mode
                return;
            }
            ArrayObjectAdapter row = mRows.get(mAccountHeaderId);
            // Clear any account row cards that are not "Location" or "Security".
            String dontDelete[] = new String[2];
            dontDelete[0] = mContext.getString(R.string.system_location);
            dontDelete[1] = mContext.getString(R.string.system_security);
            int i = 0;
            while (i < row.size ()) {
                MenuItem menuItem = (MenuItem) row.get(i);
                String title = menuItem.getTitle ();
                boolean deleteItem = true;
                for (int j = 0; j < dontDelete.length; ++j) {
                    if (title.equals(dontDelete[j])) {
                        deleteItem = false;
                        break;
                    }
                }
                if (deleteItem) {
                    row.removeItems(i, 1);
                } else {
                    ++i;
                }
            }
            // Add accounts to end of row.
            addAccounts(row);
        }
    }

    void updateAccessories() {
        synchronized (mGuard) {
            updateAccessories(R.id.accessories);
        }
    }

    public void updateWifi(final boolean isEthernetAvailable) {
        if (mWifiItem != null) {
            int index = mWifiRow.indexOf(mWifiItem);
            if (index >= 0) {
                mWifiItem = new MenuItem.Builder().from(mWifiItem)
                        .title(mContext.getString(isEthernetAvailable
                                    ? R.string.connectivity_network : R.string.connectivity_wifi))
                        .build();
                mWifiRow.replace(index, mWifiItem);
            }
        }
    }

    private boolean isInputSettingNeeded() {
        TvInputManager manager = (TvInputManager) mContext.getSystemService(
                Context.TV_INPUT_SERVICE);
        if (manager != null) {
            for (TvInputInfo input : manager.getTvInputList()) {
                if (input.isPassthroughInput()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void updateAccessories(int headerId) {
        ArrayObjectAdapter row = mRows.get(headerId);
        row.clear();

        addAccessories(row);

        // Add new accessory activity icon
        ComponentName componentName = new ComponentName("com.android.tv.settings",
                "com.android.tv.settings.accessories.AddAccessoryActivity");
        Intent i = new Intent().setComponent(componentName);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        row.add(new MenuItem.Builder().id(mNextItemId++)
                .title(mContext.getString(R.string.accessories_add))
                .imageResourceId(mContext, R.drawable.ic_settings_bluetooth)
                .intent(i).build());
    }

    private Intent getIntent(XmlResourceParser parser, AttributeSet attrs, int headerId)
            throws org.xmlpull.v1.XmlPullParserException, IOException {
        Intent intent = null;
        if (parser.next() == XmlPullParser.START_TAG && "intent".equals(parser.getName())) {
            TypedArray sa = mContext.getResources()
                    .obtainAttributes(attrs, com.android.internal.R.styleable.Intent);
            String targetClass = getStringFromTypedArray(
                    sa, com.android.internal.R.styleable.Intent_targetClass);
            String targetPackage = getStringFromTypedArray(
                    sa, com.android.internal.R.styleable.Intent_targetPackage);
            String action = getStringFromTypedArray(
                    sa, com.android.internal.R.styleable.Intent_action);
            if (targetClass != null && targetPackage != null) {
                ComponentName componentName = new ComponentName(targetPackage, targetClass);
                intent = new Intent();
                intent.setComponent(componentName);
            } else if (action != null) {
                intent = new Intent(action);
            }

            XmlUtils.skipCurrentTag(parser);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        return intent;
    }

    private String getStringFromTypedArray(TypedArray sa, int resourceId) {
        String value = null;
        TypedValue tv = sa.peekValue(resourceId);
        if (tv != null && tv.type == TypedValue.TYPE_STRING) {
            if (tv.resourceId != 0) {
                value = mContext.getString(tv.resourceId);
            } else {
                value = tv.string.toString();
            }
        }
        return value;
    }

    private MenuItem.TextGetter getDescriptionTextGetterFromKey(String key) {
        if (WifiNetworksActivity.PREFERENCE_KEY.equals(key)) {
            return ConnectivityStatusTextGetter.createWifiStatusTextGetter(mContext);
        }

        if (ETHERNET_PREFERENCE_KEY.equals(key)) {
            return ConnectivityStatusTextGetter.createEthernetStatusTextGetter(mContext);
        }

        return null;
    }

    private MenuItem.UriGetter getIconUriGetterFromKey(String key) {
        if (SoundActivity.getPreferenceKey().equals(key)) {
            return new SoundActivityImageUriGetter(mContext);
        }

        if (WifiNetworksActivity.PREFERENCE_KEY.equals(key)) {
            return ConnectivityStatusIconUriGetter.createWifiStatusIconUriGetter(mContext);
        }

        return null;
    }

    private void addAccounts(ArrayObjectAdapter row) {
        AccountManager am = AccountManager.get(mContext);
        AuthenticatorDescription[] authTypes = am.getAuthenticatorTypes();
        ArrayList<String> allowableAccountTypes = new ArrayList<>(authTypes.length);
        PackageManager pm = mContext.getPackageManager();

        int googleAccountCount = 0;

        for (AuthenticatorDescription authDesc : authTypes) {
            Resources resources = null;
            try {
                resources = pm.getResourcesForApplication(authDesc.packageName);
            } catch (NameNotFoundException e) {
                Log.e(TAG, "Authenticator description with bad package name", e);
                continue;
            }

            allowableAccountTypes.add(authDesc.type);

            // Main title text comes from the authenticator description (e.g. "Google").
            String authTitle = null;
            try {
                authTitle = resources.getString(authDesc.labelId);
                if (TextUtils.isEmpty(authTitle)) {
                    authTitle = null;  // Handled later when we add the row.
                }
            } catch (NotFoundException e) {
                Log.e(TAG, "Authenticator description with bad label id", e);
            }

            Account[] accounts = am.getAccountsByType(authDesc.type);

            // Icon URI to be displayed for each account is based on the type of authenticator.
            String imageUri = null;
            if (ACCOUNT_TYPE_GOOGLE.equals(authDesc.type)) {
                googleAccountCount = accounts.length;
                imageUri = googleAccountIconUri(mContext);
            } else {
                imageUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" +
                        authDesc.packageName + '/' +
                        resources.getResourceTypeName(authDesc.iconId) + '/' +
                        resources.getResourceEntryName(authDesc.iconId))
                        .toString();
            }

            // Display an entry for each installed account we have.
            for (final Account account : accounts) {
                Intent i = new Intent(mContext, AccountSettingsActivity.class)
                        .putExtra(AccountSettingsActivity.EXTRA_ACCOUNT, account.name);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
                row.add(new MenuItem.Builder().id(mNextItemId++)
                        .title(authTitle != null ? authTitle : account.name)
                        .imageUri(imageUri)
                        .description(authTitle != null ? account.name : null)
                        .intent(i)
                        .build());
            }
        }

        // Never allow restricted profile to add accounts.
        if (!isRestricted()) {

            // If there's already a Google account installed, disallow installing a second one.
            if (googleAccountCount > 0) {
                allowableAccountTypes.remove(ACCOUNT_TYPE_GOOGLE);
            }

            // If there are available account types, add the "add account" button.
            if (!allowableAccountTypes.isEmpty()) {
                Intent i = new Intent().setComponent(new ComponentName("com.android.tv.settings",
                        "com.android.tv.settings.accounts.AddAccountWithTypeActivity"));
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
                i.putExtra(AddAccountWithTypeActivity.EXTRA_ALLOWABLE_ACCOUNT_TYPES_STRING_ARRAY,
                        allowableAccountTypes.toArray(new String[allowableAccountTypes.size()]));

                row.add(new MenuItem.Builder().id(mNextItemId++)
                        .title(mContext.getString(R.string.add_account))
                        .imageResourceId(mContext, R.drawable.ic_settings_add)
                        .intent(i).build());
            }
        }
    }

    private void addAccessories(ArrayObjectAdapter row) {
        if (mBtAdapter != null) {
            Set<BluetoothDevice> bondedDevices = mBtAdapter.getBondedDevices();
            if (DEBUG) {
                Log.d(TAG, "List of Bonded BT Devices:");
            }

            Set<String> connectedBluetoothAddresses =
                    BluetoothConnectionsManager.getConnectedSet(mContext);

            for (BluetoothDevice device : bondedDevices) {
                if (DEBUG) {
                    Log.d(TAG, "   Device name: " + device.getName() + " , Class: " +
                            device.getBluetoothClass().getDeviceClass());
                }

                int resourceId = AccessoryUtils.getImageIdForDevice(device);
                Intent i = BluetoothAccessoryActivity.getIntent(mContext, device.getAddress(),
                        device.getName(), resourceId);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);

                String desc = connectedBluetoothAddresses.contains(device.getAddress())
                        ? mContext.getString(R.string.accessory_connected)
                        : null;

                row.add(new MenuItem.Builder().id(mNextItemId++).title(device.getName())
                        .description(desc).imageResourceId(mContext, resourceId)
                        .intent(i).build());
            }
        }
    }

    private static String googleAccountIconUri(Context context) {
        ShortcutIconResource iconResource = new ShortcutIconResource();
        iconResource.packageName = context.getPackageName();
        iconResource.resourceName = context.getResources().getResourceName(
                R.drawable.ic_settings_google_account);
        return UriUtils.getShortcutIconResourceUri(iconResource).toString();
    }
}
