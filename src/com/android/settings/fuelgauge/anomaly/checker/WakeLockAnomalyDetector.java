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

package com.android.settings.fuelgauge.anomaly.checker;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.BatteryStats;
import android.os.SystemClock;
import android.support.annotation.VisibleForTesting;
import android.util.ArrayMap;

import com.android.internal.os.BatterySipper;
import com.android.internal.os.BatteryStatsHelper;
import com.android.settings.Utils;
import com.android.settings.fuelgauge.BatteryUtils;
import com.android.settings.fuelgauge.anomaly.Anomaly;
import com.android.settings.fuelgauge.anomaly.AnomalyDetectionPolicy;
import com.android.settings.fuelgauge.anomaly.AnomalyUtils;
import com.android.settings.fuelgauge.anomaly.action.AnomalyAction;

import java.util.ArrayList;
import java.util.List;

/**
 * Check whether apps holding wakelock too long
 */
public class WakeLockAnomalyDetector implements AnomalyDetector {
    private static final String TAG = "WakeLockAnomalyChecker";
    private PackageManager mPackageManager;
    private Context mContext;
    @VisibleForTesting
    BatteryUtils mBatteryUtils;
    @VisibleForTesting
    long mWakeLockThresholdMs;
    @VisibleForTesting
    AnomalyAction mAnomalyAction;

    public WakeLockAnomalyDetector(Context context) {
        this(context, new AnomalyDetectionPolicy(context));
    }

    @VisibleForTesting
    WakeLockAnomalyDetector(Context context, AnomalyDetectionPolicy policy) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mBatteryUtils = BatteryUtils.getInstance(context);
        mAnomalyAction = AnomalyUtils.getInstance(context).getAnomalyAction(
                Anomaly.AnomalyType.WAKE_LOCK);

        mWakeLockThresholdMs = policy.wakeLockThreshold;
    }

    @Override
    public List<Anomaly> detectAnomalies(BatteryStatsHelper batteryStatsHelper) {
        return detectAnomalies(batteryStatsHelper, null);
    }

    @Override
    public List<Anomaly> detectAnomalies(BatteryStatsHelper batteryStatsHelper,
            String targetPackageName) {
        final List<BatterySipper> batterySippers = batteryStatsHelper.getUsageList();
        final List<Anomaly> anomalies = new ArrayList<>();
        final long rawRealtime = SystemClock.elapsedRealtime();
        final int targetUid = mBatteryUtils.getPackageUid(targetPackageName);

        // Check the app one by one
        for (int i = 0, size = batterySippers.size(); i < size; i++) {
            final BatterySipper sipper = batterySippers.get(i);
            final BatteryStats.Uid uid = sipper.uidObj;
            if (uid == null
                    || mBatteryUtils.shouldHideSipper(sipper)
                    || (targetUid != BatteryUtils.UID_NULL && targetUid != uid.getUid())) {
                continue;
            }

            final ArrayMap<String, ? extends BatteryStats.Uid.Wakelock> wakelocks =
                    uid.getWakelockStats();
            long maxPartialWakeLockMs = 0;

            for (int iw = wakelocks.size() - 1; iw >= 0; iw--) {
                final BatteryStats.Timer timer = wakelocks.valueAt(iw).getWakeTime(
                        BatteryStats.WAKE_TYPE_PARTIAL);
                if (timer == null) {
                    continue;
                }
                maxPartialWakeLockMs = Math.max(maxPartialWakeLockMs,
                        getTotalDurationMs(timer, rawRealtime));
            }

            // Report application as anomaly if wakelock time is too long
            // TODO(b/38233034): add more attributes to detect wakelock anomaly
            if (maxPartialWakeLockMs > mWakeLockThresholdMs) {
                final String packageName = mBatteryUtils.getPackageName(uid.getUid());
                final CharSequence displayName = Utils.getApplicationLabel(mContext,
                        packageName);

                Anomaly anomaly = new Anomaly.Builder()
                        .setUid(uid.getUid())
                        .setType(Anomaly.AnomalyType.WAKE_LOCK)
                        .setDisplayName(displayName)
                        .setPackageName(packageName)
                        .build();

                if (mAnomalyAction.isActionActive(anomaly)) {
                    anomalies.add(anomaly);
                }
            }

        }
        return anomalies;
    }

    @VisibleForTesting
    long getTotalDurationMs(BatteryStats.Timer timer, long rawRealtime) {
        if (timer == null) {
            return 0;
        }
        return timer.getTotalDurationMsLocked(rawRealtime);
    }
}