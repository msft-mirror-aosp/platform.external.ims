/*-
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following disclaimer
 *   in the documentation and/or other materials provided with the
 *   distribution.
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.service.ims;

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsService;
import android.telephony.ims.feature.RcsFeature;
import android.util.Log;

public class ImsServiceImpl extends ImsService {
    public static final String TAG = "ImsServiceImpl";
    private static final int INVALID_SLOT_ID = -1;
    private static final int UNINITIALIZED_VALUE = -1;
    private int mNumPhonesCache = UNINITIALIZED_VALUE;
    private RcsFeature mRcsFeature[];
    protected final Object mLock = new Object();

    private int getNumSlots() {
        if (mNumPhonesCache == UNINITIALIZED_VALUE) {
            mNumPhonesCache = ((TelephonyManager) getSystemService(
                    Context.TELEPHONY_SERVICE)).getPhoneCount();
        }
        return mNumPhonesCache;
    }

    private void setup() {
        final int numSlots = getNumSlots();
        mRcsFeature = new RcsFeatureImpl[numSlots];
        for (int i = 0; i < numSlots; i++) {
            mRcsFeature[i] = new RcsFeatureImpl(this, i);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "ImsService created!");
        setup();
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (android.telephony.ims.ImsService.SERVICE_INTERFACE.equals(intent.getAction())) {
            Log.d(TAG, "Returning mImsServiceController for ImsService binding");
            return mImsServiceController;
        }
        Log.e(TAG, "Invalid Intent action in onBind: " + intent.getAction());
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "ImsService destroyed!");
    }

    /**
     * When called, the framework is requesting that a new {@link RcsFeature} is created for the
     * specified slot.
     *
     * @param slotId The slot ID that the RCS Feature is being created for.
     * @return The newly created {@link RcsFeature} associated with the slot or null if the feature
     * is not supported.
     */
    @Override
    public RcsFeature createRcsFeature(int slotId) {
        Log.d(TAG, "createRcsFeature :: slotId=" + slotId + " numSlots=" + mNumPhonesCache);
        if (slotId > INVALID_SLOT_ID && slotId < getNumSlots()) {
            return mRcsFeature[slotId];
        }
        Log.e(TAG, "createRcsFeature :: Invalid slotId " + slotId);
        return null;
    }
}
