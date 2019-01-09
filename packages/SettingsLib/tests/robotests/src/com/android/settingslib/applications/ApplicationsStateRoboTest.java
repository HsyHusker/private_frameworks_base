/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.applications;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.shadow.api.Shadow.extract;

import android.annotation.UserIdInt;
import android.app.ApplicationPackageManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.IconDrawableFactory;

import com.android.settingslib.applications.ApplicationsState.AppEntry;
import com.android.settingslib.applications.ApplicationsState.Callbacks;
import com.android.settingslib.applications.ApplicationsState.Session;
import com.android.settingslib.testutils.shadow.ShadowUserManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowContextImpl;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowUserManager.class,
        ApplicationsStateRoboTest.ShadowIconDrawableFactory.class,
        ApplicationsStateRoboTest.ShadowPackageManager.class})
public class ApplicationsStateRoboTest {

    private final static String HOME_PACKAGE_NAME = "com.android.home";
    private final static String LAUNCHABLE_PACKAGE_NAME = "com.android.launchable";

    /** Class under test */
    private ApplicationsState mApplicationsState;
    private Session mSession;


    @Mock
    private Callbacks mCallbacks;
    @Captor
    private ArgumentCaptor<ArrayList<AppEntry>> mAppEntriesCaptor;
    @Mock
    private StorageStatsManager mStorageStatsManager;
    @Mock
    private IPackageManager mPackageManagerService;

    @Implements(value = IconDrawableFactory.class)
    public static class ShadowIconDrawableFactory {

        @Implementation
        protected Drawable getBadgedIcon(ApplicationInfo appInfo) {
            return new ColorDrawable(0);
        }
    }

    @Implements(value = ApplicationPackageManager.class)
    public static class ShadowPackageManager extends
            org.robolectric.shadows.ShadowApplicationPackageManager {

        // test installed modules, 2 regular, 2 hidden
        private final String[] mModuleNames = {
            "test.module.1", "test.hidden.module.2", "test.hidden.module.3", "test.module.4"};
        private final List<ModuleInfo> mInstalledModules = new ArrayList<>();

        @Implementation
        protected ComponentName getHomeActivities(List<ResolveInfo> outActivities) {
            ResolveInfo resolveInfo = new ResolveInfo();
            resolveInfo.activityInfo = new ActivityInfo();
            resolveInfo.activityInfo.packageName = HOME_PACKAGE_NAME;
            resolveInfo.activityInfo.enabled = true;
            outActivities.add(resolveInfo);
            return ComponentName.createRelative(resolveInfo.activityInfo.packageName, "foo");
        }

        @Implementation
        public List<ModuleInfo> getInstalledModules(int flags) {
            if (mInstalledModules.isEmpty()) {
                for (String moduleName : mModuleNames) {
                    mInstalledModules.add(createModuleInfo(moduleName));
                }
            }
            return mInstalledModules;
        }

        public List<ResolveInfo> queryIntentActivitiesAsUser(Intent intent,
                @PackageManager.ResolveInfoFlags int flags, @UserIdInt int userId) {
            List<ResolveInfo> resolveInfos = new ArrayList<>();
            ResolveInfo resolveInfo = new ResolveInfo();
            resolveInfo.activityInfo = new ActivityInfo();
            resolveInfo.activityInfo.packageName = LAUNCHABLE_PACKAGE_NAME;
            resolveInfo.activityInfo.enabled = true;
            resolveInfo.filter = new IntentFilter();
            resolveInfo.filter.addCategory(Intent.CATEGORY_LAUNCHER);
            resolveInfos.add(resolveInfo);
            return resolveInfos;
        }

        private ModuleInfo createModuleInfo(String packageName) {
            final ModuleInfo info = new ModuleInfo();
            info.setName(packageName);
            info.setPackageName(packageName);
            // will treat any app with package name that contains "hidden" as hidden module
            info.setHidden(!TextUtils.isEmpty(packageName) && packageName.contains("hidden"));
            return info;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        // Robolectric does not know about the StorageStatsManager as a system service.
        // Registering a mock of this service as a replacement.
        ShadowContextImpl shadowContext = Shadow.extract(
                RuntimeEnvironment.application.getBaseContext());
        shadowContext.setSystemService(Context.STORAGE_STATS_SERVICE, mStorageStatsManager);
        StorageStats storageStats = new StorageStats();
        storageStats.codeBytes = 10;
        storageStats.dataBytes = 20;
        storageStats.cacheBytes = 30;
        when(mStorageStatsManager.queryStatsForPackage(any(UUID.class),
                anyString(), any(UserHandle.class))).thenReturn(storageStats);

        // Set up 3 installed apps, in which 1 is hidden module
        final List<ApplicationInfo> infos = new ArrayList<>();
        infos.add(createApplicationInfo("test.package.1"));
        infos.add(createApplicationInfo("test.hidden.module.2"));
        infos.add(createApplicationInfo("test.package.3"));
        when(mPackageManagerService.getInstalledApplications(
            anyInt() /* flags */, anyInt() /* userId */)).thenReturn(new ParceledListSlice(infos));

        ApplicationsState.sInstance = null;
        mApplicationsState =
            ApplicationsState.getInstance(RuntimeEnvironment.application, mPackageManagerService);
        mApplicationsState.clearEntries();

        mSession = mApplicationsState.newSession(mCallbacks);
    }

    @After
    public void tearDown() {
        mSession.onDestroy();
    }

    private ApplicationInfo createApplicationInfo(String packageName) {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.sourceDir = "foo";
        appInfo.flags |= ApplicationInfo.FLAG_INSTALLED;
        appInfo.storageUuid = UUID.randomUUID();
        appInfo.packageName = packageName;
        return appInfo;
    }

    private AppEntry createAppEntry(ApplicationInfo appInfo, int id) {
        AppEntry appEntry = new AppEntry(RuntimeEnvironment.application, appInfo, id);
        appEntry.label = "label";
        appEntry.mounted = true;
        return appEntry;
    }

    private void addApp(String packageName, int id) {
        ApplicationInfo appInfo = createApplicationInfo(packageName);
        AppEntry appEntry = createAppEntry(appInfo, id);
        mApplicationsState.mAppEntries.add(appEntry);
        mApplicationsState.mEntriesMap.get(0).put(appInfo.packageName, appEntry);
    }

    private void processAllMessages() {
        Handler mainHandler = mApplicationsState.mMainHandler;
        Handler bkgHandler = mApplicationsState.mBackgroundHandler;
        ShadowLooper shadowBkgLooper = extract(bkgHandler.getLooper());
        ShadowLooper shadowMainLooper = extract(mainHandler.getLooper());
        shadowBkgLooper.idle();
        shadowMainLooper.idle();
    }

    private AppEntry findAppEntry(List<AppEntry> appEntries, long id) {
        for (AppEntry appEntry : appEntries) {
            if (appEntry.id == id) {
                return appEntry;
            }
        }
        return null;
    }

    @Test
    public void testDefaultSessionLoadsAll() {
        mSession.onResume();

        addApp(HOME_PACKAGE_NAME, 1);
        addApp(LAUNCHABLE_PACKAGE_NAME, 2);
        mSession.rebuild(ApplicationsState.FILTER_EVERYTHING, ApplicationsState.SIZE_COMPARATOR);
        processAllMessages();
        verify(mCallbacks).onRebuildComplete(mAppEntriesCaptor.capture());

        List<AppEntry> appEntries = mAppEntriesCaptor.getValue();
        assertThat(appEntries.size()).isEqualTo(2);

        for (AppEntry appEntry : appEntries) {
            assertThat(appEntry.size).isGreaterThan(0L);
            assertThat(appEntry.icon).isNotNull();
        }

        AppEntry homeEntry = findAppEntry(appEntries, 1);
        assertThat(homeEntry.isHomeApp).isTrue();
        assertThat(homeEntry.hasLauncherEntry).isFalse();

        AppEntry launchableEntry = findAppEntry(appEntries, 2);
        assertThat(launchableEntry.hasLauncherEntry).isTrue();
        assertThat(launchableEntry.launcherEntryEnabled).isTrue();
    }

    @Test
    public void testCustomSessionLoadsIconsOnly() {
        mSession.setSessionFlags(ApplicationsState.FLAG_SESSION_REQUEST_ICONS);
        mSession.onResume();

        addApp(LAUNCHABLE_PACKAGE_NAME, 1);
        mSession.rebuild(ApplicationsState.FILTER_EVERYTHING, ApplicationsState.SIZE_COMPARATOR);
        processAllMessages();
        verify(mCallbacks).onRebuildComplete(mAppEntriesCaptor.capture());

        List<AppEntry> appEntries = mAppEntriesCaptor.getValue();
        assertThat(appEntries.size()).isEqualTo(1);

        AppEntry launchableEntry = findAppEntry(appEntries, 1);
        assertThat(launchableEntry.icon).isNotNull();
        assertThat(launchableEntry.size).isEqualTo(-1);
        assertThat(launchableEntry.hasLauncherEntry).isFalse();
    }

    @Test
    public void testCustomSessionLoadsSizesOnly() {
        mSession.setSessionFlags(ApplicationsState.FLAG_SESSION_REQUEST_SIZES);
        mSession.onResume();

        addApp(LAUNCHABLE_PACKAGE_NAME, 1);
        mSession.rebuild(ApplicationsState.FILTER_EVERYTHING, ApplicationsState.SIZE_COMPARATOR);
        processAllMessages();
        verify(mCallbacks).onRebuildComplete(mAppEntriesCaptor.capture());

        List<AppEntry> appEntries = mAppEntriesCaptor.getValue();
        assertThat(appEntries.size()).isEqualTo(1);

        AppEntry launchableEntry = findAppEntry(appEntries, 1);
        assertThat(launchableEntry.icon).isNull();
        assertThat(launchableEntry.hasLauncherEntry).isFalse();
        assertThat(launchableEntry.size).isGreaterThan(0L);
    }

    @Test
    public void testCustomSessionLoadsHomeOnly() {
        mSession.setSessionFlags(ApplicationsState.FLAG_SESSION_REQUEST_HOME_APP);
        mSession.onResume();

        addApp(HOME_PACKAGE_NAME, 1);
        mSession.rebuild(ApplicationsState.FILTER_EVERYTHING, ApplicationsState.SIZE_COMPARATOR);
        processAllMessages();
        verify(mCallbacks).onRebuildComplete(mAppEntriesCaptor.capture());

        List<AppEntry> appEntries = mAppEntriesCaptor.getValue();
        assertThat(appEntries.size()).isEqualTo(1);

        AppEntry launchableEntry = findAppEntry(appEntries, 1);
        assertThat(launchableEntry.icon).isNull();
        assertThat(launchableEntry.hasLauncherEntry).isFalse();
        assertThat(launchableEntry.size).isEqualTo(-1);
        assertThat(launchableEntry.isHomeApp).isTrue();
    }

    @Test
    public void testCustomSessionLoadsLeanbackOnly() {
        mSession.setSessionFlags(ApplicationsState.FLAG_SESSION_REQUEST_LEANBACK_LAUNCHER);
        mSession.onResume();

        addApp(LAUNCHABLE_PACKAGE_NAME, 1);
        mSession.rebuild(ApplicationsState.FILTER_EVERYTHING, ApplicationsState.SIZE_COMPARATOR);
        processAllMessages();
        verify(mCallbacks).onRebuildComplete(mAppEntriesCaptor.capture());

        List<AppEntry> appEntries = mAppEntriesCaptor.getValue();
        assertThat(appEntries.size()).isEqualTo(1);

        AppEntry launchableEntry = findAppEntry(appEntries, 1);
        assertThat(launchableEntry.icon).isNull();
        assertThat(launchableEntry.size).isEqualTo(-1);
        assertThat(launchableEntry.isHomeApp).isFalse();
        assertThat(launchableEntry.hasLauncherEntry).isTrue();
        assertThat(launchableEntry.launcherEntryEnabled).isTrue();
    }

    @Test
    public void onResume_shouldNotIncludeSystemHiddenModule() {
        mSession.onResume();

        final List<ApplicationInfo> mApplications = mApplicationsState.mApplications;
        assertThat(mApplications).hasSize(2);
        assertThat(mApplications.get(0).packageName).isEqualTo("test.package.1");
        assertThat(mApplications.get(1).packageName).isEqualTo("test.package.3");
    }

}
