/*
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
 *
 */

package com.android.service.ims;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.telephony.SubscriptionManager;
import android.telephony.ims.feature.CapabilityChangeRequest;
import android.telephony.ims.feature.CapabilityChangeRequest.CapabilityPair;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.RcsFeature;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.telephony.ims.stub.RcsPresenceExchangeImplBase;
import android.telephony.ims.stub.RcsSipOptionsImplBase;
import android.util.Log;

import com.android.ims.RcsManager;
import com.android.internal.telephony.TelephonyIntents;

import java.util.List;

public class RcsFeatureImpl extends RcsFeature {

    private static final String TAG = "RcsFeatureImpl";

    private static final String RCS_PACKAGE = "com.android.service.ims";
    private static final String RCS_CLASS = "com.android.service.ims.RcsService";
    private Context mContext;
    private Handler mFeatureCallbackHandler;
    private HandlerThread mFeatureHandlerThread;
    private RcsImsCapabilities mRcsImsCapabilities;
    private int mPhoneId;
    private int mSubId;
    private int mDefaultVoiceSubId;
    private SubscriptionManager mSubscriptionManager;
    private SubscriptionChangedListener mSubscriptionListener;
    private RcsPresenceExchangeImplBase mRcsPresenceExchangeBase;
    private RcsStackAdaptor mRcsStackAdaptor;

    // Used to synchronize mSubId and mDefaultVoiceSubId
    private Object mToken = new Object();


    public RcsFeatureImpl(Context context, int phoneId) {
        mContext = context;
        mPhoneId = phoneId;
        mFeatureHandlerThread = new HandlerThread(this + "FeatureHandlerThread");
        mFeatureHandlerThread.start();
        mFeatureCallbackHandler = new Handler(mFeatureHandlerThread.getLooper());
        mDefaultVoiceSubId = SubscriptionManager.getDefaultSubscriptionId();
        mRcsPresenceExchangeBase = new RcsPresenceExchangeImpl(mContext);
        mRcsStackAdaptor = RcsStackAdaptor.getInstance(mContext);
        mRcsImsCapabilities = new RcsImsCapabilities(RcsImsCapabilities.CAPABILITY_TYPE_NONE);
        mSubscriptionManager = (SubscriptionManager) mContext.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        mSubId = getSubId();
        mSubscriptionListener = new SubscriptionChangedListener();
        mSubscriptionManager.addOnSubscriptionsChangedListener(mSubscriptionListener);

        registerVoiceSubscriptionChange();
        registerRcsStateReceiver();
        initFeatureState();
        logd("RcsFeatureImpl constructor mSubId:" + mSubId + ", "
                + "mDefaultVoiceSubId:" + mDefaultVoiceSubId);

    }

    private int getSubId() {
        int subId[] = mSubscriptionManager.getSubscriptionIds(mPhoneId);
        return subId != null ? subId[0] : SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    private void registerVoiceSubscriptionChange() {
        IntentFilter intentFilter = new IntentFilter
                (TelephonyIntents.ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED);
        mContext.registerReceiver(mVoiceSubscriptionReceiver, intentFilter);
    }

    private void initFeatureState() {
        // In DSDS environment, RcsFeatureImpl is only available for default voice subId.
        if (isDefaultVoiceSubId()) {
            logd("set STATE_READY");
            setFeatureState(ImsFeature.STATE_READY);
        } else {
            logd("set STATE_UNAVAILABLE");
            setFeatureState(ImsFeature.STATE_UNAVAILABLE);
        }
    }

    private boolean isDefaultVoiceSubId() {
        synchronized (mToken) {
            return SubscriptionManager.isValidSubscriptionId(mSubId) &&
                    SubscriptionManager.isValidSubscriptionId(mDefaultVoiceSubId) &&
                    mSubscriptionManager.isActiveSubscriptionId(mSubId) &&
                    mSubscriptionManager.isActiveSubscriptionId(mDefaultVoiceSubId) &&
                    mSubId == mDefaultVoiceSubId;
        }
    }

    private void startRcsService() {
        ComponentName comp = new ComponentName(RCS_PACKAGE, RCS_CLASS);
        ComponentName service = mContext.startService(new Intent().setComponent(comp));
        if (service == null) {
            Log.e(TAG, "Could Not Start Service " + comp.toString());
        } else {
            Log.d(TAG, comp.toString() + " started Successfully");
        }
    }

    @Override
    public void onFeatureReady() {
        logd("onFeatureReady");
        mRcsPresenceExchangeBase.initialize(this);
        mRcsStackAdaptor.setRcsPresenceExchangeImplBase(mRcsPresenceExchangeBase);
        mRcsStackAdaptor.init();
        startRcsService();
    }

    @Override
    public void onFeatureRemoved() {
        logd("onFeatureRemoved");
        mContext.unregisterReceiver(mRcsStateReceiver);
        mContext.unregisterReceiver(mVoiceSubscriptionReceiver);
        mRcsStackAdaptor.setRcsPresenceExchangeImplBase(null);
    }

    private void registerRcsStateReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(RcsManager.ACTION_RCS_SERVICE_AVAILABLE);
        intentFilter.addAction(RcsManager.ACTION_RCS_SERVICE_UNAVAILABLE);
        intentFilter.addAction(RcsManager.ACTION_RCS_SERVICE_DIED);
        mContext.registerReceiver(mRcsStateReceiver, intentFilter);
    }

    /**
     * Retrieve the implementation of UCE presence for this {@link RcsFeature}.
     * Will only be requested by the framework if presence exchang is configured as capable during
     * a {@link #changeEnabledCapabilities(CapabilityChangeRequest, CapabilityCallbackProxy)}
     * operation and the RcsFeature sets the status of the capability to true using
     * {@link #notifyCapabilitiesStatusChanged(RcsImsCapabilities)}.
     *
     * @return An instance of {@link RcsPresenceExchangeImplBase} that implements presence
     * exchange if it is supported by the device.
     * @hide
     */
    @Override
    public RcsPresenceExchangeImplBase getPresenceExchangeImpl() {
        return mRcsPresenceExchangeBase;
    }

    /**
     * Retrieve the implementation of SIP OPTIONS for this {@link RcsFeature}.
     * <p>
     * Will only be requested by the framework if capability exchange via SIP OPTIONS is
     * configured as capable during a
     * {@link #changeEnabledCapabilities(CapabilityChangeRequest, CapabilityCallbackProxy)}
     * operation and the RcsFeature sets the status of the capability to true using
     * {@link #notifyCapabilitiesStatusChanged(RcsImsCapabilities)}.
     *
     * @return An instance of {@link RcsSipOptionsImplBase} that implements SIP options exchange if
     * it is supported by the device.
     * @hide
     */
    @Override
    public RcsSipOptionsImplBase getOptionsExchangeImpl() {
        return null;
    }

    @Override
    public void changeEnabledCapabilities(CapabilityChangeRequest request,
            CapabilityCallbackProxy c) {
        logd("changeEnabledCapabilities");
        if (request == null || c == null) {
            loge("changeEnabledCapabilities :: Invalid argument(s).");
            return;
        }

        List<CapabilityPair> capsToEnable = request.getCapabilitiesToEnable();
        List<CapabilityPair> capsToDisable = request.getCapabilitiesToDisable();
        if (capsToEnable.isEmpty() && capsToDisable.isEmpty()) {
            loge("changeEnabledCapabilities :: No CapabilityPair objects to process!");
            return;
        }

        for (CapabilityPair cp : capsToEnable) {
            if (cp.getCapability() == RcsImsCapabilities.CAPABILITY_TYPE_OPTIONS_UCE) {
                // SIP OPTION is not supported
                callBackError(cp, c);
            } else if (cp.getCapability() == RcsImsCapabilities.CAPABILITY_TYPE_PRESENCE_UCE) {
                mRcsImsCapabilities.addCapabilities(
                        RcsImsCapabilities.CAPABILITY_TYPE_PRESENCE_UCE);
                enableRcsPresence();
            }
        }
        for (CapabilityPair cp : capsToDisable) {
            if (cp.getCapability() == RcsImsCapabilities.CAPABILITY_TYPE_OPTIONS_UCE) {
                // SIP OPTION is not supported
                callBackError(cp, c);
            } else if (cp.getCapability() == RcsImsCapabilities.CAPABILITY_TYPE_PRESENCE_UCE) {
                mRcsImsCapabilities.removeCapabilities(RcsImsCapabilities
                        .CAPABILITY_TYPE_PRESENCE_UCE);
                disableRcsPresence();
            }
        }
    }

    private void callBackError(CapabilityPair cp, CapabilityCallbackProxy c) {
        final Runnable r = new Runnable() {
            @Override
            public void run() {
                c.onChangeCapabilityConfigurationError(cp.getCapability(), cp.getRadioTech(),
                        ImsFeature.CAPABILITY_ERROR_GENERIC);
            }
        };
        if (mFeatureCallbackHandler != null) {
            mFeatureCallbackHandler.post(r);
        }
    }

    private void enableRcsPresence() {
        RcsManager rcsManager = getRcsManager();
        if (rcsManager != null) {
            rcsManager.setPresenceEnabledByFramework(true);
        }
        if (rcsManager != null && rcsManager.isRcsServiceAvailable()) {
            Intent intent = new Intent(RcsManager.ACTION_RCS_SERVICE_AVAILABLE);
            mContext.sendBroadcast(intent,
                    "com.android.ims.rcs.permission.STATUS_CHANGED");
        }
    }

    private void disableRcsPresence() {
        RcsManager rcsManager = getRcsManager();
        if (rcsManager != null) {
            rcsManager.setPresenceEnabledByFramework(false);
        }
        Intent intent = new Intent(RcsManager.ACTION_RCS_SERVICE_UNAVAILABLE);
        mContext.sendBroadcast(intent,
                "com.android.ims.rcs.permission.STATUS_CHANGED");
    }

    private RcsManager getRcsManager() {
        if (isDefaultVoiceSubId()) {
            return RcsManager.getInstance(mContext, mSubId);
        } else {
            return null;
        }
    }

    @Override
    public boolean queryCapabilityConfiguration(
            @RcsImsCapabilities.RcsImsCapabilityFlag int capability,
            @ImsRegistrationImplBase.ImsRegistrationTech int radioTech) {
        logi("queryCapabilityConfiguration :: capability=" + capability
                + " radioTech=" + radioTech);
        return mRcsImsCapabilities != null && mRcsImsCapabilities.isCapable(capability);
    }

    private final BroadcastReceiver mRcsStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            RcsFeatureImpl.this.logi("onReceive(), intent: " + intent + ", context: " + context);
            String action = intent.getAction();
            RcsManager rcsManager = getRcsManager();
            if (RcsManager.ACTION_RCS_SERVICE_AVAILABLE.equals(action)) {
                // Check if both enableRcsPresence() and StackListener's serviceAvailable() are
                // executed.
                if (rcsManager != null && rcsManager.isRcsServiceAvailable()) {
                    notifyCapabilitiesStatusChanged(new RcsImsCapabilities(RcsImsCapabilities
                            .CAPABILITY_TYPE_PRESENCE_UCE));
                }
            } else if (RcsManager.ACTION_RCS_SERVICE_UNAVAILABLE.equals(action) ||
                    RcsManager.ACTION_RCS_SERVICE_DIED.equals(action)) {
                // Triggered from disabledRcsPresence() or StackListener's serviceUnAvailable().
                if (rcsManager != null) {
                    notifyCapabilitiesStatusChanged(new RcsImsCapabilities(RcsImsCapabilities
                            .CAPABILITY_TYPE_NONE));
                }
            }
        }
    };

    private final BroadcastReceiver mVoiceSubscriptionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (TelephonyIntents.ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED.equals(action)) {
                int newVoiceSubId = SubscriptionManager.getDefaultSubscriptionId();
                synchronized (mToken) {
                    if (newVoiceSubId != mDefaultVoiceSubId) {
                        mDefaultVoiceSubId = newVoiceSubId;
                        mSubId = getSubId();
                        logd("mVoiceSubscriptionReceiver mSubId:" + mSubId + " "
                                + "mDefaultVoiceSubId:" + mDefaultVoiceSubId);
                        processFeatureChange();
                    }
                }
            }
        }
    };

    class SubscriptionChangedListener extends SubscriptionManager.OnSubscriptionsChangedListener {
        @Override
        public void onSubscriptionsChanged() {
            int newSubId = getSubId();
            synchronized (mToken) {
                if (newSubId != mSubId) {
                    mSubId = newSubId;
                    mDefaultVoiceSubId = SubscriptionManager.getDefaultSubscriptionId();
                    logd("onSubscriptionsChanged mSubId:" + mSubId + " mDefaultVoiceSubId:" +
                            mDefaultVoiceSubId);
                    processFeatureChange();
                }
            }
        }
    }

    private void processFeatureChange() {
        if (isDefaultVoiceSubId()) {
            logd("set STATE_READY");
            setFeatureState(ImsFeature.STATE_READY);
        } else {
            logd("set STATE_UNAVAILABLE");
            setFeatureState(ImsFeature.STATE_UNAVAILABLE);
        }
    }

    private void logd(String s) {
        Log.d(TAG, "[" + mPhoneId + "][" + mSubId + "] " + s);
    }

    private void logi(String s) {
        Log.i(TAG, "[" + mPhoneId + "][" + mSubId + "] " + s);
    }

    private void loge(String s) {
        Log.e(TAG, "[" + mPhoneId + "][" + mSubId + "] " + s);
    }
}
