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

package com.android.service.ims;

import android.content.Context;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ProvisioningManager;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsRegistrationImplBase;

import com.android.ims.internal.Logger;

import java.util.List;

public class RcsSettingUtils {
    static private Logger logger = Logger.getLogger("RcsSettingUtils");

    // Values taken from ImsConfig - Should define in @SystemApi as well.
    /**
     * SIP T1 timer value in milliseconds. See RFC 3261 for definition.
     * Value is in Integer format.
     */
    private static final int SIP_T1_TIMER = 7;
    /**
     * Whether or not capability discovery is provisioned.
     */
    private static final int CAPABILITY_DISCOVERY_ENABLED = 17;
    /**
     * period of time the availability information of a contact is cached on device.
     * Value is in Integer format.
     */
    private static final int AVAILABILITY_CACHE_EXPIRATION = 19;
    /**
     * Minimum time between two published messages from the device.
     * Value is in Integer format.
     */
    private static final int SOURCE_THROTTLE_PUBLISH = 21;
    /**
     * The Maximum number of MDNs contained in one Request Contained List.
     * Value is in Integer format.
     */
    private static final int MAX_NUMENTRIES_IN_RCL = 22;
    /**
     * Expiration timer for subscription of a Request Contained List, used in capability
     * polling.
     * Value is in Integer format.
     */
    private static final int CAPAB_POLL_LIST_SUB_EXP = 23;
    /**
     * Provisioning status for Enhanced Address Book (EAB)
     * Value is in Integer format.
     */
    private static final int EAB_SETTING_ENABLED = 25;
    /**
     * Whether or not mobile data is enabled currently.
     */
    private static final int MOBILE_DATA_ENABLED = 29;

    public static boolean isVowifiProvisioned(Context context) {
        try {
            boolean isProvisioned;
            ProvisioningManager manager = ProvisioningManager.createForSubscriptionId(
                    getDefaultSubscriptionId(context));
            isProvisioned = manager.getProvisioningStatusForCapability(
                    MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE,
                    ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN);
            logger.debug("isVowifiProvisioned=" + isProvisioned);
            return isProvisioned;
        } catch (Exception e) {
            logger.debug("isVowifiProvisioned, exception = " + e.getMessage());
            return false;
        }
    }

    public static boolean isLvcProvisioned(Context context) {
        try {
            boolean isProvisioned;
            ProvisioningManager manager = ProvisioningManager.createForSubscriptionId(
                    getDefaultSubscriptionId(context));
            isProvisioned = manager.getProvisioningStatusForCapability(
                    MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VIDEO,
                    ImsRegistrationImplBase.REGISTRATION_TECH_LTE);
            logger.debug("isLvcProvisioned=" + isProvisioned);
            return isProvisioned;
        } catch (Exception e) {
            logger.debug("isLvcProvisioned, exception = " + e.getMessage());
            return false;
        }
    }

    public static boolean isEabProvisioned(Context context) {
        boolean isProvisioned = false;
        int subId = getDefaultSubscriptionId(context);
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            logger.debug("isEabProvisioned: no valid subscriptions!");
            return false;
        }
        CarrierConfigManager configManager = (CarrierConfigManager)
                context.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager != null) {
            PersistableBundle config = configManager.getConfigForSubId(subId);
            if (config != null && !config.getBoolean(
                    CarrierConfigManager.KEY_CARRIER_VOLTE_PROVISIONED_BOOL)) {
                // If we don't need provisioning, just return true.
                return true;
            }
        }
        try {
            ProvisioningManager manager = ProvisioningManager.createForSubscriptionId(subId);
            isProvisioned = manager.getProvisioningIntValue(EAB_SETTING_ENABLED)
                    == ProvisioningManager.PROVISIONING_VALUE_ENABLED;
        } catch (Exception e) {
            logger.debug("isEabProvisioned: exception=" + e.getMessage());
        }
        logger.debug("isEabProvisioned=" + isProvisioned);
        return isProvisioned;
    }

    public static int getSIPT1Timer(Context context) {
        int sipT1Timer = 0;
        try {
            ProvisioningManager manager = ProvisioningManager.createForSubscriptionId(
                    getDefaultSubscriptionId(context));
            sipT1Timer = manager.getProvisioningIntValue(SIP_T1_TIMER);
        } catch (Exception e) {
            // If there is no active subscriptions, this will throw an exception.
            logger.debug("getSIPT1Timer: exception=" + e.getMessage());
        }
        logger.debug("getSIPT1Timer=" + sipT1Timer);
        return sipT1Timer;
    }

    /**
     * Capability discovery status of Enabled (1), or Disabled (0).
     */
    public static boolean getCapabilityDiscoveryEnabled(Context context) {
        boolean capabilityDiscoveryEnabled = false;
        try {
            ProvisioningManager manager = ProvisioningManager.createForSubscriptionId(
                    getDefaultSubscriptionId(context));
            capabilityDiscoveryEnabled = manager.getProvisioningIntValue(CAPABILITY_DISCOVERY_ENABLED)
                    == ProvisioningManager.PROVISIONING_VALUE_ENABLED;
        } catch (Exception e) {
            // If there is no active subscriptions, this will throw an exception.
            logger.debug("capabilityDiscoveryEnabled: exception=" + e.getMessage());
        }
        logger.debug("capabilityDiscoveryEnabled=" + capabilityDiscoveryEnabled);
        return capabilityDiscoveryEnabled;
    }

    /**
     * The Maximum number of MDNs contained in one Request Contained List.
     */
    public static int getMaxNumbersInRCL(Context context) {
        int maxNumbersInRCL = 100;
        try {
            ProvisioningManager manager = ProvisioningManager.createForSubscriptionId(
                    getDefaultSubscriptionId(context));
            maxNumbersInRCL = manager.getProvisioningIntValue(MAX_NUMENTRIES_IN_RCL);
        } catch (Exception e) {
            // If there is no active subscriptions, this will throw an exception.
            logger.debug("getMaxNumbersInRCL: exception=" + e.getMessage());
        }
        logger.debug("getMaxNumbersInRCL=" + maxNumbersInRCL);
        return maxNumbersInRCL;
    }

    /**
     * Expiration timer for subscription of a Request Contained List, used in capability polling.
     */
    public static int getCapabPollListSubExp(Context context) {
        int capabPollListSubExp = 30;
        try {
            ProvisioningManager manager = ProvisioningManager.createForSubscriptionId(
                    getDefaultSubscriptionId(context));
            capabPollListSubExp = manager.getProvisioningIntValue(CAPAB_POLL_LIST_SUB_EXP);
        } catch (Exception e) {
            // If there is no active subscriptions, this will throw an exception.
            logger.debug("getCapabPollListSubExp: exception=" + e.getMessage());
        }
        logger.debug("getCapabPollListSubExp=" + capabPollListSubExp);
        return capabPollListSubExp;
    }

    /**
     * Period of time the availability information of a contact is cached on device.
     */
    public static int getAvailabilityCacheExpiration(Context context) {
        int availabilityCacheExpiration = 30;
        try {
            ProvisioningManager manager = ProvisioningManager.createForSubscriptionId(
                    getDefaultSubscriptionId(context));
            availabilityCacheExpiration = manager.getProvisioningIntValue(
                    AVAILABILITY_CACHE_EXPIRATION);
        } catch (Exception e) {
            // If there is no active subscriptions, this will throw an exception.
            logger.debug("getAvailabilityCacheExpiration: exception=" + e.getMessage());
        }
        logger.debug("getAvailabilityCacheExpiration=" + availabilityCacheExpiration);
        return availabilityCacheExpiration;
    }

    public static boolean isMobileDataEnabled(Context context) {
        boolean mobileDataEnabled = false;
        try {
            ProvisioningManager manager = ProvisioningManager.createForSubscriptionId(
                    getDefaultSubscriptionId(context));
            mobileDataEnabled = manager.getProvisioningIntValue(MOBILE_DATA_ENABLED)
                    == ProvisioningManager.PROVISIONING_VALUE_ENABLED;
        } catch (Exception e) {
            // If there is no active subscriptions, this will throw an exception.
            logger.debug("isMobileDataEnabled: exception=" + e.getMessage());
        }
        logger.debug("mobileDataEnabled=" + mobileDataEnabled);
        return mobileDataEnabled;
    }

    public static void setMobileDataEnabled(Context context, boolean mobileDataEnabled) {
        logger.debug("mobileDataEnabled=" + mobileDataEnabled);
        try {
            ProvisioningManager manager = ProvisioningManager.createForSubscriptionId(
                    getDefaultSubscriptionId(context));
            manager.setProvisioningIntValue(MOBILE_DATA_ENABLED, mobileDataEnabled ?
                    ProvisioningManager.PROVISIONING_VALUE_ENABLED :
                    ProvisioningManager.PROVISIONING_VALUE_DISABLED);
        } catch (Exception e) {
            // If there is no active subscriptions, this will throw an exception.
            logger.debug("mobileDataEnabled: exception=" + e.getMessage());
        }
    }

    public static int getPublishThrottle(Context context) {
        // Default
        int publishThrottle = 60000;
        try {
            ProvisioningManager manager = ProvisioningManager.createForSubscriptionId(
                    getDefaultSubscriptionId(context));
            publishThrottle = manager.getProvisioningIntValue(SOURCE_THROTTLE_PUBLISH);
        } catch (Exception e) {
            // If there is no active subscriptions, this will throw an exception.
            logger.debug("publishThrottle: exception=" + e.getMessage());
        }
        logger.debug("publishThrottle=" + publishThrottle);
        return publishThrottle;
    }

    private static int getDefaultSubscriptionId(Context context) {
        SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
        List<SubscriptionInfo> infos = sm.getActiveSubscriptionInfoList();
        if (infos == null || infos.isEmpty()) {
            // There are no active subscriptions right now.
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
        // This code does not support MSIM unfortunately, so only provide presence on the default
        // subscription that the user chose.
        int defaultSub = SubscriptionManager.getDefaultSubscriptionId();
        // If the user has no default set, just pick the first as backup.
        if (defaultSub == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            for (SubscriptionInfo info : infos) {
                if (!info.isOpportunistic()) {
                    defaultSub = info.getSubscriptionId();
                    break;
                }
            }
        }
        return defaultSub;
    }
}

