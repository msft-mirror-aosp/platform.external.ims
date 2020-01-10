/*
 * Copyright (c) 2015, Motorola Mobility LLC
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     - Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     - Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     - Neither the name of Motorola Mobility nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL MOTOROLA MOBILITY LLC BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
 * DAMAGE.
 */

package com.android.service.ims.presence;

import android.annotation.IntDef;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.telecom.PhoneAccount;
import android.telecom.TelecomManager;
import android.telephony.AccessNetworkConstants;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.ims.RcsContactUceCapability;
import android.telephony.ims.feature.MmTelFeature;

import com.android.ims.ImsConfig;
import com.android.ims.ImsManager;
import com.android.ims.ResultCode;
import com.android.ims.internal.Logger;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyIntents;
import com.android.service.ims.RcsSettingUtils;
import com.android.service.ims.Task;
import com.android.service.ims.TaskManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class PresencePublication extends PresenceBase {
    private Logger logger = Logger.getLogger(this.getClass().getName());

    private final Object mSyncObj = new Object();

    boolean mMovedToIWLAN = false;
    boolean mMovedToLTE = false;
    boolean mVoPSEnabled = false;

    boolean mIsVolteAvailable = false;
    boolean mIsVtAvailable = false;
    boolean mIsVoWifiAvailable = false;
    boolean mIsViWifiAvailable = false;

    // Queue for the pending PUBLISH request. Just need cache the last one.
    volatile PublishRequest mPendingRequest = null;
    volatile PublishRequest mPublishingRequest = null;
    volatile PublishRequest mPublishedRequest = null;

    /*
     * Message sent to mMsgHandler when there is a new request to Publish capabilities.
     */
    private static final int MESSAGE_RCS_PUBLISH_REQUEST = 1;

    private PresencePublisher mPresencePublisher;
    private PresenceSubscriber mSubscriber = null;
    static private PresencePublication sPresencePublication = null;
    private BroadcastReceiver mReceiver = null;

    private boolean mHasCachedTrigger = false;
    private boolean mGotTriggerFromStack = false;
    private boolean mDonotRetryUntilPowerCycle = false;
    private boolean mSimLoaded = false;
    private int mPreferredTtyMode = Phone.TTY_MODE_OFF;

    private boolean mImsRegistered = false;
    private boolean mVtEnabled = false;
    private boolean mDataEnabled = false;
    private final String[] mConfigVolteProvisionErrorOnPublishResponse;
    private final String[] mConfigRcsProvisionErrorOnPublishResponse;

    /** ETag expired. */
    public static final int UCE_PRES_PUBLISH_TRIGGER_ETAG_EXPIRED = 0;
    /** Move to LTE with VoPS disabled. */
    public static final int UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_LTE_VOPS_DISABLED = 1;
    /** Move to LTE with VoPS enabled. */
    public static final int UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_LTE_VOPS_ENABLED = 2;
    /** Move to eHRPD. */
    public static final int UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_EHRPD = 3;
    /** Move to HSPA+. */
    public static final int UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_HSPAPLUS = 4;
    /** Move to 3G. */
    public static final int UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_3G = 5;
    /** Move to 2G. */
    public static final int UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_2G = 6;
    /** Move to WLAN */
    public static final int UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_WLAN = 7;
    /** Move to IWLAN */
    public static final int UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_IWLAN = 8;
    /** Trigger is unknown. */
    public static final int UCE_PRES_PUBLISH_TRIGGER_UNKNOWN = 9;

    @IntDef(value = {
            UCE_PRES_PUBLISH_TRIGGER_ETAG_EXPIRED,
            UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_LTE_VOPS_DISABLED,
            UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_LTE_VOPS_ENABLED,
            UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_EHRPD,
            UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_HSPAPLUS,
            UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_3G,
            UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_2G,
            UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_WLAN,
            UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_IWLAN,
            UCE_PRES_PUBLISH_TRIGGER_UNKNOWN
    }, prefix="UCE_PRES_PUBLISH_TRIGGER_")
    @Retention(RetentionPolicy.SOURCE)
    public @interface StackPublishTriggerType {}

    public class PublishType {
        public static final int PRES_PUBLISH_TRIGGER_DATA_CHANGED = 0;
        // the lower layer should send trigger when enable volte
        // the lower layer should unpublish when disable volte
        // so only handle VT here.
        public static final int PRES_PUBLISH_TRIGGER_VTCALL_CHANGED = 1;
        public static final int PRES_PUBLISH_TRIGGER_CACHED_TRIGGER = 2;
        public static final int PRES_PUBLISH_TRIGGER_TTY_ENABLE_STATUS = 3;
        public static final int PRES_PUBLISH_TRIGGER_RETRY = 4;
        public static final int PRES_PUBLISH_TRIGGER_FEATURE_AVAILABILITY_CHANGED = 5;
    };

    public PresencePublication(PresencePublisher presencePublisher, Context context,
            String[] configVolteProvisionErrorOnPublishResponse,
            String[] configRcsProvisionErrorOnPublishResponse) {
        super(context);
        logger.debug("PresencePublication constrcuct");
        this.mPresencePublisher = presencePublisher;
        mConfigVolteProvisionErrorOnPublishResponse = configVolteProvisionErrorOnPublishResponse;
        mConfigRcsProvisionErrorOnPublishResponse = configRcsProvisionErrorOnPublishResponse;

        mVtEnabled = getImsManager().isVtEnabledByUser();

        mDataEnabled = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.MOBILE_DATA, 1) == 1;
        new Thread(() -> {
            RcsSettingUtils.setMobileDataEnabled(mContext, mDataEnabled);
        }).start();
        logger.debug("The current mobile data is: " + (mDataEnabled ? "enabled" : "disabled"));

        mPreferredTtyMode = Settings.Secure.getInt(
                mContext.getContentResolver(),
                Settings.Secure.PREFERRED_TTY_MODE,
                Phone.TTY_MODE_OFF);
        logger.debug("The current TTY mode is: " + mPreferredTtyMode);

        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                logger.print("onReceive intent=" + intent);
                if(TelephonyIntents.ACTION_SIM_STATE_CHANGED.equalsIgnoreCase(intent.getAction())){
                    String stateExtra = intent.getStringExtra(
                            IccCardConstants.INTENT_KEY_ICC_STATE);
                    logger.print("ACTION_SIM_STATE_CHANGED INTENT_KEY_ICC_STATE=" + stateExtra);
                    final TelephonyManager teleMgr = (TelephonyManager) context.getSystemService(
                                        Context.TELEPHONY_SERVICE);
                    if(IccCardConstants.INTENT_VALUE_ICC_LOADED.equalsIgnoreCase(stateExtra)) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                if(teleMgr == null){
                                    logger.error("teleMgr = null");
                                    return;
                                }

                                int count = 0;
                                // retry 2 minutes to get the information from ISIM and RUIM
                                for(int i=0; i<60; i++) {
                                     String[] myImpu = teleMgr.getIsimImpu();
                                     String myDomain = teleMgr.getIsimDomain();
                                     String line1Number = teleMgr.getLine1Number();
                                     if(line1Number != null && line1Number.length() != 0 ||
                                         myImpu != null && myImpu.length != 0 &&
                                         myDomain != null && myDomain.length() != 0){
                                         mSimLoaded = true;
                                         // treate hot SIM hot swap as power on.
                                         mDonotRetryUntilPowerCycle = false;
                                         if(mHasCachedTrigger) {
                                             requestLocalPublish(PublishType.
                                                     PRES_PUBLISH_TRIGGER_CACHED_TRIGGER);
                                         }
                                         break;
                                     }else{
                                         try{
                                             Thread.sleep(2000);//retry 2 seconds later
                                         }catch(InterruptedException e){
                                         }
                                     }
                                 }
                            }
                        }, "wait for ISIM and RUIM").start();
                    }else if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.
                            equalsIgnoreCase(stateExtra)) {
                        // pulled out the SIM, set it as the same as power on status:
                        logger.print("Pulled out SIM, set to PUBLISH_STATE_NOT_PUBLISHED");

                        // only reset when the SIM gets absent.
                        reset();
                        mSimLoaded = false;
                        setPublishState(PUBLISH_STATE_NOT_PUBLISHED);
                    }
                }else if(Intent.ACTION_AIRPLANE_MODE_CHANGED.equalsIgnoreCase(intent.getAction())){
                    boolean airplaneMode = intent.getBooleanExtra("state", false);
                    if(airplaneMode){
                        logger.print("Airplane mode, set to PUBLISH_STATE_NOT_PUBLISHED");
                        reset();
                        setPublishState(PUBLISH_STATE_NOT_PUBLISHED);
                    }
                }else if(TelecomManager.ACTION_TTY_PREFERRED_MODE_CHANGED.equalsIgnoreCase(
                        intent.getAction())){
                    int newPreferredTtyMode = intent.getIntExtra(
                            TelecomManager.EXTRA_TTY_PREFERRED_MODE,
                            TelecomManager.TTY_MODE_OFF);
                    newPreferredTtyMode = telecomTtyModeToPhoneMode(newPreferredTtyMode);
                    logger.debug("Tty mode changed from " + mPreferredTtyMode
                            + " to " + newPreferredTtyMode);

                    boolean mIsTtyEnabled = isTtyEnabled(mPreferredTtyMode);
                    boolean isTtyEnabled = isTtyEnabled(newPreferredTtyMode);
                    mPreferredTtyMode = newPreferredTtyMode;
                    if (mIsTtyEnabled != isTtyEnabled) {
                        logger.print("ttyEnabled status changed from " + mIsTtyEnabled
                                + " to " + isTtyEnabled);
                        requestLocalPublish(PublishType.
                                PRES_PUBLISH_TRIGGER_TTY_ENABLE_STATUS);
                    }
                } else if(ImsConfig.ACTION_IMS_FEATURE_CHANGED.equalsIgnoreCase(
                        intent.getAction())){
                    int item = intent.getIntExtra(ImsConfig.EXTRA_CHANGED_ITEM, -1);
                    if ((ImsConfig.ConfigConstants.VLT_SETTING_ENABLED == item) ||
                            (ImsConfig.ConfigConstants.LVC_SETTING_ENABLED == item) ||
                            (ImsConfig.ConfigConstants.EAB_SETTING_ENABLED == item)) {
                        handleProvisionChanged();
                    }
                }
            }
        };

        IntentFilter statusFilter = new IntentFilter();
        statusFilter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        statusFilter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        statusFilter.addAction(TelecomManager.ACTION_TTY_PREFERRED_MODE_CHANGED);
        statusFilter.addAction(ImsConfig.ACTION_IMS_FEATURE_CHANGED);
        mContext.registerReceiver(mReceiver, statusFilter);

        sPresencePublication = this;
    }

    private boolean isIPVoiceSupported(boolean volteAvailable, boolean vtAvailable,
            boolean voWifiAvailable, boolean viWifiAvailable) {
        ImsManager imsManager = getImsManager();
        // volte and vowifi can be enabled separately
        if(!imsManager.isVolteEnabledByPlatform() && !imsManager.isWfcEnabledByPlatform()) {
            logger.print("Disabled by platform, voiceSupported=false");
            return false;
        }

        if(!imsManager.isVolteProvisionedOnDevice() &&
                !RcsSettingUtils.isVowifiProvisioned(mContext)) {
            logger.print("Wasn't provisioned, voiceSupported=false");
            return false;
        }

        if(!imsManager.isEnhanced4gLteModeSettingEnabledByUser() &&
                !imsManager.isWfcEnabledByUser()){
            logger.print("User didn't enable volte or wfc, voiceSupported=false");
            return false;
        }

        // for IWLAN. All WFC settings and provision should be fine if voWifiAvailable is true.
        if(isOnIWLAN()) {
            boolean voiceSupported=volteAvailable || voWifiAvailable;
            logger.print("on IWLAN, voiceSupported=" + voiceSupported);
            return voiceSupported;
        }

        // for none-IWLAN
        if(!isOnLTE()) {
            logger.print("isOnLTE=false, voiceSupported=false");
            return false;
        }

        if(!mVoPSEnabled) {
            logger.print("mVoPSEnabled=false, voiceSupported=false");
            return false;
        }

        logger.print("voiceSupported=true");
        return true;
    }

    private boolean isIPVideoSupported(boolean volteAvailable, boolean vtAvailable,
            boolean voWifiAvailable, boolean viWifiAvailable) {
        ImsManager imsManager = getImsManager();
        // if volte or vt was disabled then the viwifi will be disabled as well.
        if(!imsManager.isVolteEnabledByPlatform() ||
                !imsManager.isVtEnabledByPlatform()) {
            logger.print("Disabled by platform, videoSupported=false");
            return false;
        }

        if(!imsManager.isVolteProvisionedOnDevice() ||
                !RcsSettingUtils.isLvcProvisioned(mContext)) {
            logger.print("Not provisioned. videoSupported=false");
            return false;
        }

        if(!imsManager.isEnhanced4gLteModeSettingEnabledByUser() ||
                !mVtEnabled){
            logger.print("User disabled volte or vt, videoSupported=false");
            return false;
        }

        if(isTtyOn()){
            logger.print("isTtyOn=true, videoSupported=false");
            return false;
        }

        // for IWLAN, all WFC settings and provision should be fine if viWifiAvailable is true.
        if(isOnIWLAN()) {
            boolean videoSupported = vtAvailable || viWifiAvailable;
            logger.print("on IWLAN, videoSupported=" + videoSupported);
            return videoSupported;
        }

        if(!isDataEnabled()) {
            logger.print("isDataEnabled()=false, videoSupported=false");
            return false;
        }

        if(!isOnLTE()) {
            logger.print("isOnLTE=false, videoSupported=false");
            return false;
        }

        if(!mVoPSEnabled) {
            logger.print("mVoPSEnabled=false, videoSupported=false");
            return false;
        }

        return true;
    }

    public boolean isTtyOn() {
        logger.debug("isTtyOn settingsTtyMode=" + mPreferredTtyMode);
        return isTtyEnabled(mPreferredTtyMode);
    }

    public void onImsConnected() {
        mImsRegistered = true;
    }

    public void onImsDisconnected() {
        logger.debug("reset PUBLISH status for IMS had been disconnected");
        mImsRegistered = false;
        reset();
    }

    private void reset() {
        mIsVolteAvailable = false;
        mIsVtAvailable = false;
        mIsVoWifiAvailable = false;
        mIsViWifiAvailable = false;

        synchronized(mSyncObj) {
            mPendingRequest = null; //ignore the previous request
            mPublishingRequest = null;
            mPublishedRequest = null;
        }
    }

    public void handleImsServiceUp() {
        reset();
    }

    public void handleImsServiceDown() {
    }

    private void handleProvisionChanged() {
        if(RcsSettingUtils.isEabProvisioned(mContext)) {
            logger.debug("provisioned, set mDonotRetryUntilPowerCycle to false");
            mDonotRetryUntilPowerCycle = false;
            if(mHasCachedTrigger) {
                requestLocalPublish(PublishType.PRES_PUBLISH_TRIGGER_CACHED_TRIGGER);
            }
        }
    }

    static public PresencePublication getPresencePublication() {
        return sPresencePublication;
    }

    public void setSubscriber(PresenceSubscriber subscriber) {
        mSubscriber = subscriber;
    }

    public boolean isDataEnabled() {
        return  Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.MOBILE_DATA, 1) == 1;
    }

    public void onMobileDataChanged(boolean value){
        logger.print("onMobileDataChanged, mDataEnabled=" + mDataEnabled + " value=" + value);
        if(mDataEnabled != value) {
            mDataEnabled = value;
            RcsSettingUtils.setMobileDataEnabled(mContext, mDataEnabled);

            requestLocalPublish(PublishType.PRES_PUBLISH_TRIGGER_DATA_CHANGED);
        }
    }

    public void onVtEnabled(boolean enabled) {
        logger.debug("onVtEnabled mVtEnabled=" + mVtEnabled + " enabled=" + enabled);

        if(mVtEnabled != enabled) {
            mVtEnabled = enabled;
            requestLocalPublish(PublishType.PRES_PUBLISH_TRIGGER_VTCALL_CHANGED);
        }
    }

    @Override
    public void onCommandStatusUpdated(int taskId, int requestId, int resultCode) {
        logger.info("onCommandStatusUpdated: resultCode= " + resultCode);
        super.onCommandStatusUpdated(taskId, requestId, resultCode);
    }

    /**
     * @return return true if it had been PUBLISHED.
     */
    public boolean isPublishedOrPublishing() {
        long publishThreshold = RcsSettingUtils.getPublishThrottle(mContext);

        boolean publishing = false;
        publishing = (mPublishingRequest != null) &&
                (System.currentTimeMillis() - mPublishingRequest.getTimestamp()
                <= publishThreshold);

        return (getPublishState() == PUBLISH_STATE_200_OK || publishing);
    }

    /**
     * @return The result of the last Publish request.
     */
    public @PresenceBase.PresencePublishState int getPublishState() {
        if (mPresencePublisher != null) {
            return mPresencePublisher.getPublisherState();
        }
        return PUBLISH_STATE_NOT_PUBLISHED;
    }

    /**
     * @param publishState The result of the last publish request.
     */
    public void setPublishState(int publishState) {
        if (mPresencePublisher != null) {
            mPresencePublisher.updatePublisherState(publishState);
        }
    }

    // Trigger a local publish based off of state changes in the framework.
    private void requestLocalPublish(int trigger) {

        // if the value is true then it will call stack to send the PUBLISH
        // though the previous publish had the same capability
        // for example: for retry PUBLISH.
        boolean bForceToNetwork = true;
        switch(trigger)
        {
            case PublishType.PRES_PUBLISH_TRIGGER_DATA_CHANGED:
            {
                logger.print("PRES_PUBLISH_TRIGGER_DATA_CHANGED");
                bForceToNetwork = false;
                break;
            }
            case PublishType.PRES_PUBLISH_TRIGGER_VTCALL_CHANGED:
            {
                logger.print("PRES_PUBLISH_TRIGGER_VTCALL_CHANGED");
                // Didn't get featureCapabilityChanged sometimes.
                bForceToNetwork = true;
                break;
            }
            case PublishType.PRES_PUBLISH_TRIGGER_CACHED_TRIGGER:
            {
                logger.print("PRES_PUBLISH_TRIGGER_CACHED_TRIGGER");
                break;
            }
            case PublishType.PRES_PUBLISH_TRIGGER_TTY_ENABLE_STATUS:
            {
                logger.print("PRES_PUBLISH_TRIGGER_TTY_ENABLE_STATUS");
                bForceToNetwork = true;

                break;
            }
            case PublishType.PRES_PUBLISH_TRIGGER_FEATURE_AVAILABILITY_CHANGED:
            {
                bForceToNetwork = false;
                logger.print("PRES_PUBLISH_TRIGGER_FEATURE_AVAILABILITY_CHANGED");
                break;
            }
            case PublishType.PRES_PUBLISH_TRIGGER_RETRY:
            {
                logger.print("PRES_PUBLISH_TRIGGER_RETRY");
                break;
            }
            default:
            {
                logger.print("Unknown publish trigger from AP");
            }
        }

        if(mGotTriggerFromStack == false){
            // Waiting for RCS stack get ready.
            logger.print("Didn't get trigger from stack yet, discard framework trigger.");
            return;
        }

        if (mDonotRetryUntilPowerCycle) {
            logger.print("Don't publish until next power cycle");
            return;
        }

        if(!mSimLoaded){
            //need to read some information from SIM to publish
            logger.print("invokePublish cache the trigger since the SIM is not ready");
            mHasCachedTrigger = true;
            return;
        }

        //the provision status didn't be read from modem yet
        if(!RcsSettingUtils.isEabProvisioned(mContext)) {
            logger.print("invokePublish cache the trigger, not provision yet");
            mHasCachedTrigger = true;
            return;
        }

        PublishRequest publishRequest = new PublishRequest(
                bForceToNetwork, System.currentTimeMillis());

        requestPublication(publishRequest);

        return;
    }

    public void onStackPublishRequested(@StackPublishTriggerType int publishTriggerType) {
        mGotTriggerFromStack = true;

        switch (publishTriggerType)
        {
            case UCE_PRES_PUBLISH_TRIGGER_ETAG_EXPIRED:
            {
                logger.print("PUBLISH_TRIGGER_ETAG_EXPIRED");
                break;
            }
            case UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_LTE_VOPS_DISABLED:
            {
                logger.print("PUBLISH_TRIGGER_MOVE_TO_LTE_VOPS_DISABLED");
                mMovedToLTE = true;
                mVoPSEnabled = false;
                mMovedToIWLAN = false;

                // onImsConnected could came later than this trigger.
                mImsRegistered = true;
                break;
            }
            case UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_LTE_VOPS_ENABLED:
            {
                logger.print("PUBLISH_TRIGGER_MOVE_TO_LTE_VOPS_ENABLED");
                mMovedToLTE = true;
                mVoPSEnabled = true;
                mMovedToIWLAN = false;

                // onImsConnected could came later than this trigger.
                mImsRegistered = true;
                break;
            }
            case UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_IWLAN:
            {
                logger.print("QRCS_PRES_PUBLISH_TRIGGER_MOVE_TO_IWLAN");
                mMovedToLTE = false;
                mVoPSEnabled = false;
                mMovedToIWLAN = true;

                // onImsConnected could came later than this trigger.
                mImsRegistered = true;
                break;
            }
            case UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_EHRPD:
            {
                logger.print("PUBLISH_TRIGGER_MOVE_TO_EHRPD");
                mMovedToLTE = false;
                mVoPSEnabled = false;
                mMovedToIWLAN = false;

                // onImsConnected could came later than this trigger.
                mImsRegistered = true;
                break;
            }
            case UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_HSPAPLUS:
            {
                logger.print("PUBLISH_TRIGGER_MOVE_TO_HSPAPLUS");
                mMovedToLTE = false;
                mVoPSEnabled = false;
                mMovedToIWLAN = false;
                break;
            }
            case UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_2G:
            {
                logger.print("PUBLISH_TRIGGER_MOVE_TO_2G");
                mMovedToLTE = false;
                mVoPSEnabled = false;
                mMovedToIWLAN = false;
                break;
            }
            case UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_3G:
            {
                logger.print("PUBLISH_TRIGGER_MOVE_TO_3G");
                mMovedToLTE = false;
                mVoPSEnabled = false;
                mMovedToIWLAN = false;
                break;
            }
            default:
                logger.print("Unknow Publish Trigger Type");
        }

        if (mDonotRetryUntilPowerCycle) {
            logger.print("Don't publish until next power cycle");
            return;
        }

        if(!mSimLoaded){
            //need to read some information from SIM to publish
            logger.print("invokePublish cache the trigger since the SIM is not ready");
            mHasCachedTrigger = true;
            return;
        }

        //the provision status didn't be read from modem yet
        if(!RcsSettingUtils.isEabProvisioned(mContext)) {
            logger.print("invokePublish cache the trigger, not provision yet");
            mHasCachedTrigger = true;
            return;
        }

        // Always send the PUBLISH when we got the trigger from stack.
        // This can avoid any issue when switch the phone between IWLAN and LTE.
        PublishRequest publishRequest = new PublishRequest(
                true /*forceToNetwork*/, System.currentTimeMillis());

        requestPublication(publishRequest);
    }

    /**
     * Trigger a publish when the stack becomes available and we have a cached trigger waiting.
     */
    public void onStackAvailable() {
        if (mHasCachedTrigger) {
            requestLocalPublish(PublishType.PRES_PUBLISH_TRIGGER_CACHED_TRIGGER);
        }
    }

    public class PublishRequest {
        private boolean mForceToNetwork = true;
        private long mCurrentTime = 0;
        private boolean mVolteCapable = false;
        private boolean mVtCapable = false;

        PublishRequest(boolean bForceToNetwork, long currentTime) {
            refreshPublishContent();
            mForceToNetwork = bForceToNetwork;
            mCurrentTime = currentTime;
        }

        public void refreshPublishContent() {
            mVolteCapable = isIPVoiceSupported(mIsVolteAvailable,
                mIsVtAvailable,
                mIsVoWifiAvailable,
                mIsViWifiAvailable);
            mVtCapable = isIPVideoSupported(mIsVolteAvailable,
                mIsVtAvailable,
                mIsVoWifiAvailable,
                mIsViWifiAvailable);
        }

        public boolean getForceToNetwork() {
            return mForceToNetwork;
        }

        public void setForceToNetwork(boolean bForceToNetwork) {
            mForceToNetwork = bForceToNetwork;
        }

        public long getTimestamp() {
            return mCurrentTime;
        }

        public void setTimestamp(long currentTime) {
            mCurrentTime = currentTime;
        }

        public void setVolteCapable(boolean capable) {
            mVolteCapable = capable;
        }

        public void setVtCapable(boolean capable) {
            mVtCapable = capable;
        }

        public boolean getVolteCapable() {
            return mVolteCapable;
        }

        public boolean getVtCapable() {
            return mVtCapable;
        }

        public boolean hasSamePublishContent(PublishRequest request) {
            if(request == null) {
                logger.error("request == null");
                return false;
            }

            return (mVolteCapable == request.getVolteCapable() &&
                    mVtCapable == request.getVtCapable());
        }

        public String toString(){
            return "mForceToNetwork=" + mForceToNetwork +
                    " mCurrentTime=" + mCurrentTime +
                    " mVolteCapable=" + mVolteCapable +
                    " mVtCapable=" + mVtCapable;
        }
    }

    private Handler mMsgHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);

            logger.debug( "Thread=" + Thread.currentThread().getName() + " received "
                    + msg);
            if(msg == null){
                logger.error("msg=null");
                return;
           }

            switch (msg.what) {
                case MESSAGE_RCS_PUBLISH_REQUEST:
                    logger.debug("handleMessage  msg=RCS_PUBLISH_REQUEST:");

                    PublishRequest publishRequest = (PublishRequest) msg.obj;
                    synchronized(mSyncObj) {
                        mPendingRequest = null;
                    }
                    doPublish(publishRequest);
                break;

                default:
                    logger.debug("handleMessage unknown msg=" + msg.what);
            }
        }
    };

    private void requestPublication(PublishRequest publishRequest) {
        // this is used to avoid the temp false feature change when switched between network.
        if(publishRequest == null) {
            logger.error("Invalid parameter publishRequest == null");
            return;
        }

        long requestThrottle = 2000;
        long currentTime = System.currentTimeMillis();
        synchronized(mSyncObj){
            // There is a PUBLISH will be done soon. Discard it
            if((mPendingRequest != null) && currentTime - mPendingRequest.getTimestamp()
                    <= requestThrottle) {
                logger.print("A publish is pending, update the pending request and discard this one");
                if(publishRequest.getForceToNetwork() && !mPendingRequest.getForceToNetwork()) {
                    mPendingRequest.setForceToNetwork(true);
                }
                mPendingRequest.setTimestamp(publishRequest.getTimestamp());
                return;
            }

            mPendingRequest = publishRequest;
        }

        Message publishMessage = mMsgHandler.obtainMessage(
                MESSAGE_RCS_PUBLISH_REQUEST, mPendingRequest);
        mMsgHandler.sendMessageDelayed(publishMessage, requestThrottle);
    }

    private void doPublish(PublishRequest publishRequest) {

        if(publishRequest == null) {
            logger.error("publishRequest == null");
            return;
        }

        if (mPresencePublisher == null) {
            logger.error("mPresencePublisher == null");
            return;
        }

        if(!mImsRegistered) {
            logger.debug("IMS wasn't registered");
            return;
        }

        // Since we are doing a publish, don't need the retry any more.
        if(mPendingRetry) {
            mPendingRetry = false;
            mAlarmManager.cancel(mRetryAlarmIntent);
        }

        // always publish the latest status.
        synchronized(mSyncObj) {
            publishRequest.refreshPublishContent();
        }

        logger.print("publishRequest=" + publishRequest);
        if(!publishRequest.getForceToNetwork() && isPublishedOrPublishing() &&
                (publishRequest.hasSamePublishContent(mPublishingRequest) ||
                        (mPublishingRequest == null) &&
                        publishRequest.hasSamePublishContent(mPublishedRequest)) &&
                (getPublishState() != PUBLISH_STATE_NOT_PUBLISHED)) {
            logger.print("Don't need publish since the capability didn't change publishRequest " +
                    publishRequest + " getPublishState()=" + getPublishState());
            return;
        }

        // Don't publish too frequently. Checking the throttle timer.
        if(isPublishedOrPublishing()) {
            if(mPendingRetry) {
                logger.print("Pending a retry");
                return;
            }

            long publishThreshold = RcsSettingUtils.getPublishThrottle(mContext);
            long passed = publishThreshold;
            if(mPublishingRequest != null) {
                passed = System.currentTimeMillis() - mPublishingRequest.getTimestamp();
            } else if(mPublishedRequest != null) {
                passed = System.currentTimeMillis() - mPublishedRequest.getTimestamp();
            }
            passed = passed>=0?passed:publishThreshold;

            long left = publishThreshold - passed;
            left = left>120000?120000:left; // Don't throttle more then 2 mintues.
            if(left > 0) {
                // A publish is ongoing or published in 1 minute.
                // Since the new publish has new status. So schedule
                // the publish after the throttle timer.
                scheduleRetryPublish(left);
                return;
            }
        }

        // we need send PUBLISH once even the volte is off when power on the phone.
        // That will tell other phone that it has no volte/vt capability.
        if(!getImsManager().isEnhanced4gLteModeSettingEnabledByUser() &&
                getPublishState() != PUBLISH_STATE_NOT_PUBLISHED) {
             // volte was not enabled.
             // or it is turnning off volte. lower layer should unpublish
             reset();
             return;
        }

        TelephonyManager teleMgr = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);
        if (teleMgr == null) {
             logger.error("TelephonyManager not available.");
             return;
        }
        Uri contactUri = Uri.fromParts(PhoneAccount.SCHEME_TEL, teleMgr.getLine1Number(), null);

        RcsContactUceCapability.Builder presenceInfoBuilder =
                new RcsContactUceCapability.Builder(contactUri);
        if (publishRequest.getVolteCapable()) {
            presenceInfoBuilder.add(RcsContactUceCapability.CAPABILITY_IP_VOICE_CALL);
        }
        if (publishRequest.getVtCapable()) {
            presenceInfoBuilder.add(RcsContactUceCapability.CAPABILITY_IP_VIDEO_CALL);
        }

        synchronized(mSyncObj) {
            mPublishingRequest = publishRequest;
            mPublishingRequest.setTimestamp(System.currentTimeMillis());
        }

        int ret = mPresencePublisher.requestPublication(presenceInfoBuilder.build());
        if (ret == ResultCode.ERROR_SERVICE_NOT_AVAILABLE) {
            mHasCachedTrigger = true;
        } else {
            //reset the cached trigger
            mHasCachedTrigger = false;
        }
    }

    private PendingIntent mRetryAlarmIntent = null;
    public static final String ACTION_RETRY_PUBLISH_ALARM =
            "com.android.service.ims.presence.retry.publish";
    private AlarmManager mAlarmManager = null;
    private BroadcastReceiver mRetryReceiver = null;
    boolean mCancelRetry = true;
    boolean mPendingRetry = false;

    private void scheduleRetryPublish(long timeSpan) {
        logger.print("timeSpan=" + timeSpan +
                " mPendingRetry=" + mPendingRetry +
                " mCancelRetry=" + mCancelRetry);

        // avoid duplicated retry.
        if(mPendingRetry) {
            logger.debug("There was a retry already");
            return;
        }
        mPendingRetry = true;
        mCancelRetry = false;

        Intent intent = new Intent(ACTION_RETRY_PUBLISH_ALARM);
        intent.setPackage(mContext.getPackageName());
        mRetryAlarmIntent = PendingIntent.getBroadcast(mContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        if(mAlarmManager == null) {
            mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        }

        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + timeSpan, mRetryAlarmIntent);
    }

    public void retryPublish() {
        logger.print("mCancelRetry=" + mCancelRetry);
        mPendingRetry = false;

        // Need some time to cancel it (1 minute for longest)
        // Don't do it if it was canceled already.
        if(mCancelRetry) {
            return;
        }

        requestLocalPublish(PublishType.PRES_PUBLISH_TRIGGER_RETRY);
    }

    public void onSipResponse(int requestId, int responseCode, String reasonPhrase) {
        logger.print( "Publish response code = " + responseCode
                +"Publish response reason phrase = " + reasonPhrase);

        synchronized(mSyncObj) {
            mPublishedRequest = mPublishingRequest;
            mPublishingRequest = null;
        }

        if (isInConfigList(responseCode, reasonPhrase,
                mConfigVolteProvisionErrorOnPublishResponse)) {
            logger.print("volte provision error. sipCode=" + responseCode + " phrase=" +
                    reasonPhrase);
            setPublishState(PUBLISH_STATE_VOLTE_PROVISION_ERROR);
            mDonotRetryUntilPowerCycle = true;

            notifyDm();

            return;
        }

        if (isInConfigList(responseCode, reasonPhrase, mConfigRcsProvisionErrorOnPublishResponse)) {
            logger.print("rcs provision error.sipCode=" + responseCode + " phrase=" + reasonPhrase);
            setPublishState(PUBLISH_STATE_RCS_PROVISION_ERROR);
            mDonotRetryUntilPowerCycle = true;

            return;
        }

        switch (responseCode) {
            case 999:
                logger.debug("Publish ignored - No capability change");
                break;
            case 200:
                setPublishState(PUBLISH_STATE_200_OK);
                if(mSubscriber != null) {
                    mSubscriber.retryToGetAvailability();
                }
                break;

            case 408:
                setPublishState(PUBLISH_STATE_REQUEST_TIMEOUT);
                break;

            default: // Generic Failure
                if ((responseCode < 100) || (responseCode > 699)) {
                    logger.debug("Ignore internal response code, sipCode=" + responseCode);
                    // internal error
                    //  0- PERMANENT ERROR: UI should not retry immediately
                    //  888- TEMP ERROR:  UI Can retry immediately
                    //  999- NO CHANGE: No Publish needs to be sent
                    if(responseCode == 888) {
                        // retry per 2 minutes
                        scheduleRetryPublish(120000);
                    } else {
                        logger.debug("Ignore internal response code, sipCode=" + responseCode);
                    }
                } else {
                    logger.debug( "Generic Failure");
                    setPublishState(PUBLISH_STATE_OTHER_ERROR);

                    if ((responseCode>=400) && (responseCode <= 699)) {
                        // 4xx/5xx/6xx, No retry, no impact on subsequent publish
                        logger.debug( "No Retry in OEM");
                    }
                }
                break;
        }

        // Suppose the request ID had been set when IQPresListener_CMDStatus
        Task task = TaskManager.getDefault().getTaskByRequestId(requestId);
        if(task != null){
            task.mSipResponseCode = responseCode;
            task.mSipReasonPhrase = reasonPhrase;
        }

        handleCallback(task, getPublishState(), false);
    }

    private static boolean isTtyEnabled(int mode) {
        return Phone.TTY_MODE_OFF != mode;
    }

    private static int telecomTtyModeToPhoneMode(int telecomMode) {
        switch (telecomMode) {
            case TelecomManager.TTY_MODE_FULL:
                return Phone.TTY_MODE_FULL;
            case TelecomManager.TTY_MODE_VCO:
                return Phone.TTY_MODE_VCO;
            case TelecomManager.TTY_MODE_HCO:
                return Phone.TTY_MODE_HCO;
            case TelecomManager.TTY_MODE_OFF:
            default:
                return Phone.TTY_MODE_OFF;
        }
    }

    public void finish() {
        if (mReceiver != null) {
            mContext.unregisterReceiver(mReceiver);
            mReceiver = null;
        }
    }

    protected void finalize() throws Throwable {
        finish();
    }

    public void onFeatureCapabilityChanged(int networkType,
            MmTelFeature.MmTelCapabilities capabilities) {
        logger.debug("onFeatureCapabilityChanged networkType=" + networkType
                +", capabilities=" + capabilities);

        Thread thread = new Thread(() -> onFeatureCapabilityChangedInternal(networkType,
                capabilities), "onFeatureCapabilityChangedInternal thread");

        thread.start();
    }

    synchronized private void onFeatureCapabilityChangedInternal(int networkType,
            MmTelFeature.MmTelCapabilities capabilities) {
        boolean oldIsVolteAvailable = mIsVolteAvailable;
        boolean oldIsVtAvailable = mIsVtAvailable;
        boolean oldIsVoWifiAvailable = mIsVoWifiAvailable;
        boolean oldIsViWifiAvailable = mIsViWifiAvailable;

        mIsVolteAvailable = (networkType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN) &&
                capabilities.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE);

        mIsVoWifiAvailable = (networkType == AccessNetworkConstants.TRANSPORT_TYPE_WLAN) &&
                capabilities.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE);

        mIsVtAvailable = (networkType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN) &&
                capabilities.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VIDEO);

        mIsViWifiAvailable = (networkType == AccessNetworkConstants.TRANSPORT_TYPE_WLAN) &&
                capabilities.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VIDEO);

        logger.print("mIsVolteAvailable=" + mIsVolteAvailable +
                " mIsVoWifiAvailable=" + mIsVoWifiAvailable +
                " mIsVtAvailable=" + mIsVtAvailable +
                " mIsViWifiAvailable=" + mIsViWifiAvailable +
                " oldIsVolteAvailable=" + oldIsVolteAvailable +
                " oldIsVoWifiAvailable=" + oldIsVoWifiAvailable +
                " oldIsVtAvailable=" + oldIsVtAvailable +
                " oldIsViWifiAvailable=" + oldIsViWifiAvailable);

        if(oldIsVolteAvailable != mIsVolteAvailable ||
                oldIsVtAvailable != mIsVtAvailable ||
                oldIsVoWifiAvailable != mIsVoWifiAvailable ||
                oldIsViWifiAvailable != mIsViWifiAvailable) {
            if(mGotTriggerFromStack) {
                if((Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.AIRPLANE_MODE_ON, 0) != 0) && !mIsVoWifiAvailable &&
                        !mIsViWifiAvailable) {
                    logger.print("Airplane mode was on and no vowifi and viwifi." +
                        " Don't need publish. Stack will unpublish");
                    return;
                }

                if(isOnIWLAN()) {
                    // will check duplicated PUBLISH in requestPublication by invokePublish
                    requestLocalPublish(PublishType.
                            PRES_PUBLISH_TRIGGER_FEATURE_AVAILABILITY_CHANGED);
                }
            } else {
                mHasCachedTrigger = true;
            }
        }
    }

    private boolean isOnLTE() {
        TelephonyManager teleMgr = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);
            int networkType = teleMgr.getDataNetworkType();
            logger.debug("mMovedToLTE=" + mMovedToLTE + " networkType=" + networkType);

            // Had reported LTE by trigger and still have DATA.
            return mMovedToLTE && (networkType != TelephonyManager.NETWORK_TYPE_UNKNOWN);
    }

    private boolean isOnIWLAN() {
        TelephonyManager teleMgr = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);
            int networkType = teleMgr.getDataNetworkType();
            logger.debug("mMovedToIWLAN=" + mMovedToIWLAN + " networkType=" + networkType);

            // Had reported IWLAN by trigger and still have DATA.
            return mMovedToIWLAN && (networkType != TelephonyManager.NETWORK_TYPE_UNKNOWN);
    }


    private ImsManager getImsManager() {
        return ImsManager.getInstance(mContext, SubscriptionManager.getDefaultVoicePhoneId());
    }
}
