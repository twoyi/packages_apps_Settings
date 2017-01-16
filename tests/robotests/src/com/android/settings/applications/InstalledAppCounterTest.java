/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.settings.applications;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.settings.SettingsRobolectricTestRunner;
import com.android.settings.TestConfig;
import com.android.settings.testutils.shadow.ShadowUserManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.android.settings.testutils.ApplicationTestUtils.buildInfo;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link InstalledAppCounter}.
 */
@RunWith(SettingsRobolectricTestRunner.class)
@Config(manifest = TestConfig.MANIFEST_PATH, sdk = TestConfig.SDK_VERSION,
        shadows = {ShadowUserManager.class})
public final class InstalledAppCounterTest {

    private final String APP_1 = "app1";
    private final String APP_2 = "app2";
    private final String APP_3 = "app3";
    private final String APP_4 = "app4";
    private final String APP_5 = "app5";
    private final String APP_6 = "app6";

    private final int MAIN_USER_ID = 0;
    private final int MANAGED_PROFILE_ID = 10;

    @Mock private UserManager mUserManager;
    @Mock private Context mContext;
    @Mock private PackageManagerWrapper mPackageManager;
    private List<UserInfo> mUsersToCount;

    private int mInstalledAppCount = -1;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
    }

    private void expectQueryIntentActivities(int userId, String packageName, boolean launchable) {
        when(mPackageManager.queryIntentActivitiesAsUser(
                argThat(new IsLaunchIntentFor(packageName)),
                eq(PackageManager.GET_DISABLED_COMPONENTS | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE),
                eq(userId))).thenReturn(launchable ? Arrays.asList(new ResolveInfo())
                        : new ArrayList<ResolveInfo>());
    }

    @Test
    public void testCountInstalledAppsAcrossAllUsers() {
        // There are two users.
        mUsersToCount = Arrays.asList(
                new UserInfo(MAIN_USER_ID, "main", UserInfo.FLAG_ADMIN),
                new UserInfo(MANAGED_PROFILE_ID, "managed profile", 0));

        // The first user has four apps installed:
        // * app1 is an updated system app. It should be counted.
        // * app2 is a user-installed app. It should be counted.
        // * app3 is a system app that provides a launcher icon. It should be counted.
        // * app4 is a system app that provides no launcher icon. It should not be counted.
        when(mPackageManager.getInstalledApplicationsAsUser(PackageManager.GET_DISABLED_COMPONENTS
                | PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS
                | PackageManager.MATCH_ANY_USER,
                MAIN_USER_ID)).thenReturn(Arrays.asList(
                        buildInfo(MAIN_USER_ID, APP_1, ApplicationInfo.FLAG_UPDATED_SYSTEM_APP),
                        buildInfo(MAIN_USER_ID, APP_2, 0 /* flags */),
                        buildInfo(MAIN_USER_ID, APP_3, ApplicationInfo.FLAG_SYSTEM),
                        buildInfo(MAIN_USER_ID, APP_4, ApplicationInfo.FLAG_SYSTEM)));
        // For system apps, InstalledAppCounter checks whether they handle the default launcher
        // intent to decide whether to include them in the count of installed apps or not.
        expectQueryIntentActivities(MAIN_USER_ID, APP_3, true /* launchable */);
        expectQueryIntentActivities(MAIN_USER_ID, APP_4, false /* launchable */);

        // app1, app3 and app4 are installed by enterprise policy.
        final UserHandle mainUser = new UserHandle(MAIN_USER_ID);
        when(mPackageManager.getInstallReason(APP_1, mainUser))
                .thenReturn(PackageManager.INSTALL_REASON_POLICY);
        when(mPackageManager.getInstallReason(APP_2, mainUser))
                .thenReturn(PackageManager.INSTALL_REASON_UNKNOWN);
        when(mPackageManager.getInstallReason(APP_3, mainUser))
                .thenReturn(PackageManager.INSTALL_REASON_POLICY);
        when(mPackageManager.getInstallReason(APP_4, mainUser))
                .thenReturn(PackageManager.INSTALL_REASON_POLICY);

        // The second user has four apps installed:
        // * app5 is a user-installed app. It should be counted.
        // * app6 is a system app that provides a launcher icon. It should be counted.
        when(mPackageManager.getInstalledApplicationsAsUser(PackageManager.GET_DISABLED_COMPONENTS
                | PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS,
                MANAGED_PROFILE_ID)).thenReturn(Arrays.asList(
                        buildInfo(MANAGED_PROFILE_ID, APP_5, 0 /* flags */),
                        buildInfo(MANAGED_PROFILE_ID, APP_6, ApplicationInfo.FLAG_SYSTEM)));
        expectQueryIntentActivities(MANAGED_PROFILE_ID, APP_6, true /* launchable */);

        // app5 is installed by enterprise policy.
        final UserHandle managedProfileUser = new UserHandle(MANAGED_PROFILE_ID);
        when(mPackageManager.getInstallReason(APP_5, managedProfileUser))
                .thenReturn(PackageManager.INSTALL_REASON_POLICY);
        when(mPackageManager.getInstallReason(APP_6, managedProfileUser))
                .thenReturn(PackageManager.INSTALL_REASON_UNKNOWN);

        // Count the number of all apps installed, irrespective of install reason. Wait for the
        // background task to finish.
        (new InstalledAppCounterTestable(ApplicationFeatureProvider.IGNORE_INSTALL_REASON))
                .execute();
        ShadowApplication.runBackgroundTasks();

        assertThat(mInstalledAppCount).isEqualTo(5);

        // Verify that installed packages were retrieved for the users returned by
        // InstalledAppCounterTestable.getUsersToCount() only.
        verify(mPackageManager).getInstalledApplicationsAsUser(anyInt(), eq(MAIN_USER_ID));
        verify(mPackageManager).getInstalledApplicationsAsUser(anyInt(),
                eq(MANAGED_PROFILE_ID));
        verify(mPackageManager, atLeast(0)).queryIntentActivitiesAsUser(anyObject(), anyInt(),
                anyInt());
        verifyNoMoreInteractions(mPackageManager);

        // Count once more, considering apps installed by enterprise policy only. Wait for the
        // background task to finish.
        mInstalledAppCount = 0;
        (new InstalledAppCounterTestable(PackageManager.INSTALL_REASON_POLICY)).execute();
        ShadowApplication.runBackgroundTasks();

        assertThat(mInstalledAppCount).isEqualTo(3);
    }

    private class InstalledAppCounterTestable extends InstalledAppCounter {
        public InstalledAppCounterTestable(int installReason) {
            super(mContext, installReason, mPackageManager);
        }

        @Override
        protected void onCountComplete(int num) {
            mInstalledAppCount = num;
        }

        @Override
        protected List<UserInfo> getUsersToCount() {
            return mUsersToCount;
        }
    }

    private class IsLaunchIntentFor extends ArgumentMatcher<Intent> {
        private final String mPackageName;

        IsLaunchIntentFor(String packageName) {
            mPackageName = packageName;
        }

        @Override
        public boolean matches(Object i) {
            final Intent intent = (Intent) i;
            if (intent == null) {
                return false;
            }
            if (intent.getAction() != Intent.ACTION_MAIN) {
                return false;
            }
            final Set<String> categories = intent.getCategories();
            if (categories == null || categories.size() != 1 ||
                    !categories.contains(Intent.CATEGORY_LAUNCHER)) {
                return false;
            }
            if (!mPackageName.equals(intent.getPackage())) {
                return false;
            }
            return true;
        }
    }
}
