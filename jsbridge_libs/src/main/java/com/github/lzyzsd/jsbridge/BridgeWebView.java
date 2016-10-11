package com.github.lzyzsd.jsbridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.app.ActivityCompat;
import android.support.v4.util.SimpleArrayMap;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.Log;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

@SuppressLint("SetJavaScriptEnabled")
public class BridgeWebView extends WebView implements WebViewJavascriptBridge {

  private final String TAG = "BridgeWebView";

  public static final String toLoadJs = "WebViewJavascriptBridge.js";
  SimpleArrayMap<String, BridgeHandler> messageHandlers = new SimpleArrayMap<>();
  SimpleArrayMap<String, CallBackFunction> responseCallbacks = new SimpleArrayMap<>();

  BridgeHandler defaultHandler = new DefaultHandler();

  private List<Message> startupMessage = new ArrayList<Message>();

  public List<Message> getStartupMessage() {
    return startupMessage;
  }

  public void setStartupMessage(List<Message> startupMessage) {
    this.startupMessage = startupMessage;
  }

  private long uniqueId = 0;

  public BridgeWebView(Context context, AttributeSet attrs) {
    super(context, attrs);
    init();
  }

  public BridgeWebView(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
    init();
  }

  public BridgeWebView(Context context) {
    super(context);
    init();
  }

  /**
   * @param handler default handler,handle messages send by js without assigned handler name, if js
   * message has handler name, it will be handled by named handlers registered by native
   */
  public void setDefaultHandler(BridgeHandler handler) {
    this.defaultHandler = handler;
  }

  BridgeWebViewClient bridgeWebViewClient;

  private void init() {
    WebSettings setting = this.getSettings();
    setUseragent(false);
    String appCachePath = getContext().getApplicationContext().getCacheDir().getAbsolutePath();
    setting.setAppCachePath(appCachePath);
    setting.setDomStorageEnabled(true);
    setting.setJavaScriptEnabled(true);
    setting.setAllowFileAccess(true);
    setting.setAllowContentAccess(true);
    setting.setAppCacheEnabled(true);
    setting.setDatabaseEnabled(true);
    setting.setCacheMode(WebSettings.LOAD_NO_CACHE);
    setting.setJavaScriptCanOpenWindowsAutomatically(true);
    setting.setGeolocationEnabled(true);
    this.setVerticalScrollBarEnabled(false);
    this.setHorizontalScrollBarEnabled(false);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      WebView.setWebContentsDebuggingEnabled(true);
    }
    bridgeWebViewClient = generateBridgeWebViewClient();
    this.setWebViewClient(bridgeWebViewClient);
  }

  public void setUseragent(boolean needPerm) {
    WebSettings setting = getSettings();
    String ua = setting.getUserAgentString();
    ua += ("/lianshang_android" + "/" + "1.8");
    ua += (" device_name" + "/" + DeviceTool.getDeviceName());
    ua += (" device_version/" + DeviceTool.getOSVersionName());
    try {
      if (needPerm) {
        ua +=
            (" device_id/" + DeviceTool.getDeviceID(getContext(), DeviceTool.getIMEI(getContext())));
      }
    }catch (SecurityException e){
      e.printStackTrace();
    }
    Log.i("zhjh", "useragent:" + ua);
    setting.setUserAgentString(ua);
  }

  protected BridgeWebViewClient generateBridgeWebViewClient() {
    return new BridgeWebViewClient(this);
  }

  void handlerReturnData(String url) {
    String functionName = BridgeUtil.getFunctionFromReturnUrl(url);
    CallBackFunction f = responseCallbacks.get(functionName);
    String data = BridgeUtil.getDataFromReturnUrl(url);
    if (f != null) {
      f.onCallBack(data);
      responseCallbacks.remove(functionName);
      return;
    }
  }

  @Override
  public void send(String data) {
    send(data, null);
  }

  @Override
  public void send(String data, CallBackFunction responseCallback) {
    doSend(null, data, responseCallback);
  }

  private void doSend(String handlerName, String data, CallBackFunction responseCallback) {
    Message m = new Message();
    if (!TextUtils.isEmpty(data)) {
      m.setData(data);
    }
    if (responseCallback != null) {
      String callbackStr = String.format(BridgeUtil.CALLBACK_ID_FORMAT,
          ++uniqueId + (BridgeUtil.UNDERLINE_STR + SystemClock.currentThreadTimeMillis()));
      responseCallbacks.put(callbackStr, responseCallback);
      m.setCallbackId(callbackStr);
    }
    if (!TextUtils.isEmpty(handlerName)) {
      m.setHandlerName(handlerName);
    }
    queueMessage(m);
  }

  private void queueMessage(Message m) {
    if (startupMessage != null) {
      startupMessage.add(m);
    } else {
      dispatchMessage(m);
    }
  }

  void dispatchMessage(Message m) {
    String messageJson = m.toJson();
    //escape special characters for json string
    messageJson = messageJson.replaceAll("(\\\\)([^utrn])", "\\\\\\\\$1$2");
    messageJson = messageJson.replaceAll("(?<=[^\\\\])(\")", "\\\\\"");
    String javascriptCommand = String.format(BridgeUtil.JS_HANDLE_MESSAGE_FROM_JAVA, messageJson);
    if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
      this.loadUrl(javascriptCommand);
    }
  }

  void flushMessageQueue() {
    if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
      loadUrl(BridgeUtil.JS_FETCH_QUEUE_FROM_JAVA, new CallBackFunction() {

        @Override
        public void onCallBack(String data) {
          // deserializeMessage
          List<Message> list = null;
          try {
            list = Message.toArrayList(data);
          } catch (Exception e) {
            e.printStackTrace();
            return;
          }
          if (list == null || list.size() == 0) {
            return;
          }
          for (int i = 0; i < list.size(); i++) {
            Message m = list.get(i);
            String responseId = m.getResponseId();
            // 是否是response
            if (!TextUtils.isEmpty(responseId)) {
              CallBackFunction function = responseCallbacks.get(responseId);
              String responseData = m.getResponseData();
              function.onCallBack(responseData);
              responseCallbacks.remove(responseId);
            } else {
              CallBackFunction responseFunction = null;
              // if had callbackId
              final String callbackId = m.getCallbackId();
              if (!TextUtils.isEmpty(callbackId)) {
                responseFunction = new CallBackFunction() {
                  @Override
                  public void onCallBack(String data) {
                    Message responseMsg = new Message();
                    responseMsg.setResponseId(callbackId);
                    responseMsg.setResponseData(data);
                    queueMessage(responseMsg);
                  }
                };
              } else {
                responseFunction = new CallBackFunction() {
                  @Override
                  public void onCallBack(String data) {
                    // do nothing
                  }
                };
              }
              BridgeHandler handler;
              if (!TextUtils.isEmpty(m.getHandlerName())) {
                handler = messageHandlers.get(m.getHandlerName());
              } else {
                handler = defaultHandler;
              }
              if (handler != null) {
                handler.handler(m.getData(), responseFunction);
              }
            }
          }
        }
      });
    }
  }

  public void loadUrl(String jsUrl, CallBackFunction returnCallback) {
    this.loadUrl(jsUrl);
    responseCallbacks.put(BridgeUtil.parseFunctionName(jsUrl), returnCallback);
  }

  /**
   * register handler,so that javascript can call it
   */
  public void registerHandler(String handlerName, BridgeHandler handler) {
    if (handler != null) {
      messageHandlers.put(handlerName, handler);
    }
  }

  /**
   * call javascript registered handler
   */
  public void callHandler(String handlerName, String data, CallBackFunction callBack) {
    doSend(handlerName, data, callBack);
  }
}
