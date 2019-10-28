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

import android.annotation.NonNull;
import android.content.Context;
import android.net.Uri;
import android.telephony.ims.RcsContactUceCapability;
import android.telephony.ims.stub.RcsPresenceExchangeImplBase;
import android.util.Log;

import com.android.ims.RcsManager;
import com.android.ims.RcsPresenceInfo;
import com.android.service.ims.RcsStackAdaptor;
import com.android.service.ims.TaskManager;

import java.util.List;

public class RcsPresenceExchangeImpl extends RcsPresenceExchangeImplBase {

    private Context mContext;
    private static final String TAG = "RcsPresenceExchangeImpl";

    public RcsPresenceExchangeImpl(Context context) {
        mContext = context;
    }


    /**
     * The user capabilities of one or multiple contacts have been requested by the framework.
     * <p>
     * The implementer must follow up this call with an {@link #onCommandUpdate(int, int)} call to
     * indicate whether or not this operation succeeded.  If this operation succeeds, network
     * response updates should be sent to the framework using
     * {@link #onNetworkResponse(int, String, int)}. When the operation is completed,
     * {@link #onCapabilityRequestResponse(List, int)} should be called with the presence
     * information for the contacts specified.
     *
     * @param uris           A {@link List} of the {@link Uri}s that the framework is requesting
     *                       the UCE
     *                       capabilities for.
     * @param operationToken The token associated with this operation. Updates to this request using
     *                       {@link #onCommandUpdate(int, int)},
     *                       {@link #onNetworkResponse(int, String, int)}, and
     *                       {@link #onCapabilityRequestResponse(List, int)}  must use the same
     *                       operation token
     *                       in response.
     */
    public void requestCapabilities(@NonNull List<Uri> uris, int operationToken) {
        Log.d(TAG, "requestCapabilities operationToken:" + operationToken);
        String[] contacts = new String[uris.size()];
        for (int i = 0; i < uris.size(); i++) {
            contacts[i] = uris.get(i).toString();
        }
        RcsStackAdaptor.getInstance(null).requestCapability(contacts, operationToken);
    }

    /**
     * The capabilities of this device have been updated and should be published to the network.
     * <p>
     * The implementer must follow up this call with an {@link #onCommandUpdate(int, int)} call to
     * indicate whether or not this operation succeeded. If this operation succeeds, network
     * response updates should be sent to the framework using
     * {@link #onNetworkResponse(int, String, int)}.
     *
     * @param capabilities   The capabilities for this device.
     * @param operationToken The token associated with this operation. Any subsequent
     *                       {@link #onCommandUpdate(int, int)} or
     *                       {@link #onNetworkResponse(int, String, int)}
     *                       calls regarding this update must use the same token.
     */
    public void updateCapabilities(@NonNull RcsContactUceCapability capabilities,
            int operationToken) {
        Log.d(TAG, "updateCapabilities operationToken:" + operationToken);
        boolean isVolte = capabilities.isCapable(RcsContactUceCapability
                .CAPABILITY_IP_VOICE_CALL);
        boolean isVt = capabilities.isCapable(
                RcsContactUceCapability.CAPABILITY_IP_VIDEO_CALL);
        int volteState = isVolte ? RcsPresenceInfo.ServiceState.ONLINE
                : RcsPresenceInfo.ServiceState.OFFLINE;
        int vtState = isVt ? RcsPresenceInfo.ServiceState.ONLINE
                : RcsPresenceInfo.ServiceState.OFFLINE;
        RcsPresenceInfo presenceInfo = new RcsPresenceInfo(capabilities.getContactUri().toString(),
                RcsPresenceInfo.VolteStatus.VOLTE_UNKNOWN,
                volteState,
                null,
                System.currentTimeMillis(),
                vtState,
                null,
                System.currentTimeMillis()
        );
        RcsStackAdaptor.getInstance(null).requestPublication(presenceInfo, null, operationToken);
    }

}
