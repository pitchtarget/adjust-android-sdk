//
//  RequestHandler.java
//  AdjustIo
//
//  Created by Christian Wellenbrock on 2013-06-25.
//  Copyright (c) 2013 adeven. All rights reserved.
//  See the file MIT-LICENSE for copying permission.
//

package com.adeven.adjustio;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.json.JSONObject;

public class RequestHandler extends HandlerThread {
    private static final int CONNECTION_TIMEOUT = Constants.ONE_MINUTE;
    private static final int SOCKET_TIMEOUT     = Constants.ONE_MINUTE;

    private InternalHandler internalHandler;
    private PackageHandler  packageHandler;
    private HttpClient      httpClient;

    protected RequestHandler(PackageHandler packageHandler) {
        super(Constants.LOGTAG, MIN_PRIORITY);
        setDaemon(true);
        start();

        this.internalHandler = new InternalHandler(getLooper(), this);
        this.packageHandler = packageHandler;

        Message message = Message.obtain();
        message.arg1 = InternalHandler.INIT;
        internalHandler.sendMessage(message);
    }

    protected void sendPackage(ActivityPackage pack) {
        Message message = Message.obtain();
        message.arg1 = InternalHandler.SEND;
        message.obj = pack;
        internalHandler.sendMessage(message);
    }

    private static final class InternalHandler extends Handler {
        private static final int INIT = 72401;
        private static final int SEND = 72400;

        private final WeakReference<RequestHandler> requestHandlerReference;

        protected InternalHandler(Looper looper, RequestHandler requestHandler) {
            super(looper);
            this.requestHandlerReference = new WeakReference<RequestHandler>(requestHandler);
        }

        @Override
        public void handleMessage(Message message) {
            super.handleMessage(message);

            RequestHandler requestHandler = requestHandlerReference.get();
            if (null == requestHandler) {
                return;
            }

            switch (message.arg1) {
                case INIT:
                    requestHandler.initInternal();
                    break;
                case SEND:
                    ActivityPackage activityPackage = (ActivityPackage) message.obj;
                    requestHandler.sendInternal(activityPackage);
                    break;
            }
        }
    }

    private void initInternal() {
        HttpParams httpParams = new BasicHttpParams();
        HttpConnectionParams.setConnectionTimeout(httpParams, CONNECTION_TIMEOUT);
        HttpConnectionParams.setSoTimeout(httpParams, SOCKET_TIMEOUT);
        httpClient = new DefaultHttpClient(httpParams);
    }

    private void sendInternal(ActivityPackage activityPackage) {
        try {
            HttpUriRequest request = getRequest(activityPackage);
            HttpResponse response = httpClient.execute(request);
            requestFinished(response, activityPackage);
        } catch (UnsupportedEncodingException e) {
            sendNextPackage(activityPackage, "Failed to encode parameters", e);
        } catch (ClientProtocolException e) {
            closePackage(activityPackage, "Client protocol error", e);
        } catch (SocketTimeoutException e) {
            closePackage(activityPackage, "Request timed out", e);
        } catch (IOException e) {
            closePackage(activityPackage, "Request failed", e);
        } catch (Exception e) {
            sendNextPackage(activityPackage, "Runtime exeption", e);
        }
    }

    private void requestFinished(HttpResponse response, ActivityPackage activityPackage) {
        int statusCode = response.getStatusLine().getStatusCode();
        String responseString = parseResponse(response);

        if (HttpStatus.SC_OK == statusCode) {
            Logger.info(activityPackage.getSuccessMessage());
        } else {
            Logger.error(String.format("%s. (%s)", activityPackage.getFailureMessage(), responseString));
        }

        packageHandler.sendNextPackage();
    }

    private String parseResponse(HttpResponse response) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            response.getEntity().writeTo(out);
            out.close();
            return out.toString().trim();
        } catch (Exception e) {
            Logger.error(String.format("Failed to parse response (%s)", e));
            return "Failed to parse response";
        }
    }

    private void closePackage(ActivityPackage activityPackage, String message, Throwable throwable) {
        final String failureMessage = activityPackage.getFailureMessage();
        final String errorMessage;
        if (throwable != null) {
            errorMessage = String.format("%s. (%s: %s) Will retry later.", failureMessage, message, throwable);
        } else {
            errorMessage = String.format("%s. (%s) Will retry later.", failureMessage, message);
        }
        Logger.error(errorMessage);
        packageHandler.closeFirstPackage();
    }

    private void sendNextPackage(ActivityPackage activityPackage, String message, Throwable throwable) {
        String failureMessage = activityPackage.getFailureMessage();
        if (throwable != null) {
            Logger.error(String.format("%s (%s: %s)", failureMessage, message, throwable));
        } else {
            Logger.error(String.format("%s (%s)", failureMessage, message));
        }

        packageHandler.sendNextPackage();
    }


    private HttpUriRequest getRequest(ActivityPackage activityPackage) throws UnsupportedEncodingException {
        String url = Constants.BASE_URL + activityPackage.getPath();
        HttpPost request = new HttpPost(url);

        String language = Locale.getDefault().getLanguage();
        request.addHeader("User-Agent", activityPackage.getUserAgent());
        request.addHeader("Client-Sdk", activityPackage.getClientSdk());
        request.addHeader("Accept-Language", language);

        List<NameValuePair> pairs = new ArrayList<NameValuePair>();
        for (Map.Entry<String, String> entity : activityPackage.getParameters().entrySet()) {
            NameValuePair pair = new BasicNameValuePair(entity.getKey(), entity.getValue());
            pairs.add(pair);
        }
        
        Map <String, String> deviceData = Util.getDeviceData();
        JSONObject devicePayload = new JSONObject(deviceData);
        NameValuePair devicePayloadPair = new BasicNameValuePair("device_data", devicePayload.toString());
        pairs.add(devicePayloadPair);

        UrlEncodedFormEntity entity = new UrlEncodedFormEntity(pairs);
        entity.setContentType(URLEncodedUtils.CONTENT_TYPE);
        request.setEntity(entity);

        return request;
    }
}
