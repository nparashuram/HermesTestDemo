/**
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.react;

import static com.facebook.react.uimanager.common.UIManagerType.DEFAULT;
import static com.facebook.react.uimanager.common.UIManagerType.FABRIC;
import static com.facebook.systrace.Systrace.TRACE_TAG_REACT_JAVA_BRIDGE;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import com.facebook.common.logging.FLog;
import com.facebook.infer.annotation.Assertions;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.CatalystInstance;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactMarker;
import com.facebook.react.bridge.ReactMarkerConstants;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.common.annotations.VisibleForTesting;
import com.facebook.react.modules.appregistry.AppRegistry;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.modules.deviceinfo.DeviceInfoModule;
import com.facebook.react.surface.ReactStage;
import com.facebook.react.uimanager.DisplayMetricsHolder;
import com.facebook.react.uimanager.IllegalViewOperationException;
import com.facebook.react.uimanager.JSTouchDispatcher;
import com.facebook.react.uimanager.PixelUtil;
import com.facebook.react.uimanager.RootView;
import com.facebook.react.uimanager.ReactRoot;
import com.facebook.react.uimanager.UIManagerHelper;
import com.facebook.react.uimanager.UIManagerModule;
import com.facebook.react.uimanager.common.UIManagerType;
import com.facebook.react.uimanager.events.EventDispatcher;
import com.facebook.systrace.Systrace;
import javax.annotation.Nullable;

/**
 * Default root view for catalyst apps. Provides the ability to listen for size changes so that a UI
 * manager can re-layout its elements. It delegates handling touch events for itself and child views
 * and sending those events to JS by using JSTouchDispatcher. This view is overriding {@link
 * ViewGroup#onInterceptTouchEvent} method in order to be notified about the events for all of its
 * children and it's also overriding {@link ViewGroup#requestDisallowInterceptTouchEvent} to make
 * sure that {@link ViewGroup#onInterceptTouchEvent} will get events even when some child view start
 * intercepting it. In case when no child view is interested in handling some particular touch event,
 * this view's {@link View#onTouchEvent} will still return true in order to be notified about all
 * subsequent touch events related to that gesture (in case when JS code wants to handle that
 * gesture).
 */
public class ReactRootView extends FrameLayout implements RootView, ReactRoot {

  /**
   * Listener interface for react root view events
   */
  public interface ReactRootViewEventListener {
    /**
     * Called when the react context is attached to a ReactRootView.
     */
    void onAttachedToReactInstance(ReactRootView rootView);
  }

  private @Nullable ReactInstanceManager mReactInstanceManager;
  private @Nullable String mJSModuleName;
  private @Nullable Bundle mAppProperties;
  private @Nullable String mInitialUITemplate;
  private @Nullable CustomGlobalLayoutListener mCustomGlobalLayoutListener;
  private @Nullable ReactRootViewEventListener mRootViewEventListener;
  private int mRootViewTag;
  private boolean mIsAttachedToInstance;
  private boolean mShouldLogContentAppeared;
  private @Nullable JSTouchDispatcher mJSTouchDispatcher;
  private final ReactAndroidHWInputDeviceHelper mAndroidHWInputDeviceHelper = new ReactAndroidHWInputDeviceHelper(this);
  private boolean mWasMeasured = false;
  private int mWidthMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
  private int mHeightMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
  private int mLastWidth = 0;
  private int mLastHeight = 0;
  private @UIManagerType int mUIManagerType = DEFAULT;
  //CRN BEGIN
  /**
   * CRN ReactRootView Statistic Fields
   */
  private long mStartTime = 0;
  private boolean mWasEntryRootView = false;
  private boolean mAllowStatistic = false;
  private OnReactRootViewDisplayCallback mDisplayCallback;

  public interface OnReactRootViewDisplayCallback {
    void reactRootViewPageDisplay();
  }
  /**
   * 设置是否是进入ReactRootView
   * @param isEntryView isEntryView
   */
  public void markEntryRootView(boolean isEntryView) {
    this.mWasEntryRootView = isEntryView;
  }

  /**
   * 设置是否允许统计性能数据
   * @param doStatistic doStatistic
   */
  public void setAllowStatistic(boolean doStatistic) {
    this.mAllowStatistic = doStatistic;
  }

  /**
   * 设置显示ReactRootView回调
   * @param displayCallback displayCallback
   */
  public void setReactRootViewDisplayCallback(OnReactRootViewDisplayCallback displayCallback) {
    this.mDisplayCallback = displayCallback;
  }

  // CRN END
  private final boolean mUseSurface;

  public ReactRootView(Context context) {
    super(context);
    mUseSurface = false;
    init();
  }

  public ReactRootView(Context context, boolean useSurface) {
    super(context);
    mUseSurface = useSurface;
    init();
  }

  public ReactRootView(Context context, AttributeSet attrs) {
    super(context, attrs);
    mUseSurface = false;
    init();
  }

  public ReactRootView(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
    mUseSurface = false;
    init();
  }

  private void init() {
    setClipChildren(false);
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    if (mUseSurface) {
      super.onMeasure(widthMeasureSpec, heightMeasureSpec);
      return;
    }

    Systrace.beginSection(TRACE_TAG_REACT_JAVA_BRIDGE, "ReactRootView.onMeasure");
    try {
      boolean measureSpecsUpdated = widthMeasureSpec != mWidthMeasureSpec ||
        heightMeasureSpec != mHeightMeasureSpec;
      mWidthMeasureSpec = widthMeasureSpec;
      mHeightMeasureSpec = heightMeasureSpec;

      int width = 0;
      int height = 0;
      int widthMode = MeasureSpec.getMode(widthMeasureSpec);
      if (widthMode == MeasureSpec.AT_MOST || widthMode == MeasureSpec.UNSPECIFIED) {
        for (int i = 0; i < getChildCount(); i++) {
          View child = getChildAt(i);
          int childSize =
              child.getLeft()
                  + child.getMeasuredWidth()
                  + child.getPaddingLeft()
                  + child.getPaddingRight();
          width = Math.max(width, childSize);
        }
      } else {
        width = MeasureSpec.getSize(widthMeasureSpec);
      }
      int heightMode = MeasureSpec.getMode(heightMeasureSpec);
      if (heightMode == MeasureSpec.AT_MOST || heightMode == MeasureSpec.UNSPECIFIED) {
        for (int i = 0; i < getChildCount(); i++) {
          View child = getChildAt(i);
          int childSize =
              child.getTop()
                  + child.getMeasuredHeight()
                  + child.getPaddingTop()
                  + child.getPaddingBottom();
          height = Math.max(height, childSize);
        }
      } else {
        height = MeasureSpec.getSize(heightMeasureSpec);
      }
      setMeasuredDimension(width, height);
      mWasMeasured = true;

      // Check if we were waiting for onMeasure to attach the root view.
      if (mReactInstanceManager != null && !mIsAttachedToInstance) {
        attachToReactInstanceManager();
      } else if (measureSpecsUpdated || mLastWidth != width || mLastHeight != height) {
        updateRootLayoutSpecs(mWidthMeasureSpec, mHeightMeasureSpec);
      }
      mLastWidth = width;
      mLastHeight = height;

    } finally {
      Systrace.endSection(TRACE_TAG_REACT_JAVA_BRIDGE);
    }
  }

  @Override
  public void onChildStartedNativeGesture(MotionEvent androidEvent) {
    if (mReactInstanceManager == null || !mIsAttachedToInstance ||
      mReactInstanceManager.getCurrentReactContext() == null) {
      FLog.w(
        ReactConstants.TAG,
        "Unable to dispatch touch to JS as the catalyst instance has not been attached");
      return;
    }
    if (mJSTouchDispatcher == null) {
      FLog.w(
        ReactConstants.TAG,
        "Unable to dispatch touch to JS before the dispatcher is available");
      return;
    }
    ReactContext reactContext = mReactInstanceManager.getCurrentReactContext();
    EventDispatcher eventDispatcher = reactContext.getNativeModule(UIManagerModule.class).getEventDispatcher();
    mJSTouchDispatcher.onChildStartedNativeGesture(androidEvent, eventDispatcher);
  }

  @Override
  public boolean onInterceptTouchEvent(MotionEvent ev) {
    dispatchJSTouchEvent(ev);
    return super.onInterceptTouchEvent(ev);
  }

  @Override
  public boolean onTouchEvent(MotionEvent ev) {
    dispatchJSTouchEvent(ev);
    super.onTouchEvent(ev);
    // In case when there is no children interested in handling touch event, we return true from
    // the root view in order to receive subsequent events related to that gesture
    return true;
  }

  // 增加dispatchDraw方法,统计页面渲染显示时间等等

  @Override
  protected void dispatchDraw(Canvas canvas) {
    try {
      super.dispatchDraw(canvas);

      if (mWasEntryRootView && getChildCount() > 0) {
        long showPeriod = System.currentTimeMillis() - mStartTime;
        boolean isSucA = mStartTime > 0 && showPeriod >= 0;
        boolean isSucB = mReactInstanceManager != null
                && mReactInstanceManager.getCRNInstanceInfo() != null
                && mReactInstanceManager.getCRNInstanceInfo().loadReportListener != null;

        if (mAllowStatistic) {
          if (mReactInstanceManager != null && mReactInstanceManager.getCRNInstanceInfo() != null) {
            mReactInstanceManager.getCRNInstanceInfo().isRendered = true;
          }
          if (mDisplayCallback != null) {
            mDisplayCallback.reactRootViewPageDisplay();
          }
          if (isSucA && isSucB) {
            mReactInstanceManager.getCRNInstanceInfo().renderDoneTime = System.currentTimeMillis();
            mReactInstanceManager.getCRNInstanceInfo()
                    .loadReportListener.onLoadComponentTime(mReactInstanceManager, showPeriod);
          } else {
            FLog.e("o_crn_statistic_error",
                    "isSucA:" + isSucA + "|isSucB:" + isSucB + "|period:" + showPeriod);
          }
          mAllowStatistic = false;
        }
      }
    } catch (Exception e) {
      handleException(e);
    }

  }
  // CRN END

  @Override
  public boolean dispatchKeyEvent(KeyEvent ev) {
    if (mReactInstanceManager == null || !mIsAttachedToInstance ||
      mReactInstanceManager.getCurrentReactContext() == null) {
      FLog.w(
        ReactConstants.TAG,
        "Unable to handle key event as the catalyst instance has not been attached");
      return super.dispatchKeyEvent(ev);
    }
    mAndroidHWInputDeviceHelper.handleKeyEvent(ev);
    return super.dispatchKeyEvent(ev);
  }

  @Override
  protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
    if (mReactInstanceManager == null || !mIsAttachedToInstance ||
      mReactInstanceManager.getCurrentReactContext() == null) {
      FLog.w(
        ReactConstants.TAG,
        "Unable to handle focus changed event as the catalyst instance has not been attached");
      super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
      return;
    }
    mAndroidHWInputDeviceHelper.clearFocus();
    super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
  }

  @Override
  public void requestChildFocus(View child, View focused) {
    if (mReactInstanceManager == null || !mIsAttachedToInstance ||
      mReactInstanceManager.getCurrentReactContext() == null) {
      FLog.w(
        ReactConstants.TAG,
        "Unable to handle child focus changed event as the catalyst instance has not been attached");
      super.requestChildFocus(child, focused);
      return;
    }
    mAndroidHWInputDeviceHelper.onFocusChanged(focused);
    super.requestChildFocus(child, focused);
  }

  private void dispatchJSTouchEvent(MotionEvent event) {
    if (mReactInstanceManager == null || !mIsAttachedToInstance ||
      mReactInstanceManager.getCurrentReactContext() == null) {
      FLog.w(
        ReactConstants.TAG,
        "Unable to dispatch touch to JS as the catalyst instance has not been attached");
      return;
    }
    if (mJSTouchDispatcher == null) {
      FLog.w(
        ReactConstants.TAG,
        "Unable to dispatch touch to JS before the dispatcher is available");
      return;
    }
    ReactContext reactContext = mReactInstanceManager.getCurrentReactContext();
    EventDispatcher eventDispatcher = reactContext.getNativeModule(UIManagerModule.class).getEventDispatcher();
    mJSTouchDispatcher.handleTouchEvent(event, eventDispatcher);
  }

  @Override
  public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
    // Override in order to still receive events to onInterceptTouchEvent even when some other
    // views disallow that, but propagate it up the tree if possible.
    if (getParent() != null) {
      getParent().requestDisallowInterceptTouchEvent(disallowIntercept);
    }
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    if (mUseSurface) {
      super.onLayout(changed, left, top, right, bottom);
    }
    // No-op since UIManagerModule handles actually laying out children.
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (mIsAttachedToInstance) {
      removeOnGlobalLayoutListener();
      getViewTreeObserver().addOnGlobalLayoutListener(getCustomGlobalLayoutListener());
    }
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    if (mIsAttachedToInstance) {
      removeOnGlobalLayoutListener();
    }
  }

  private void removeOnGlobalLayoutListener() {
    getViewTreeObserver().removeOnGlobalLayoutListener(getCustomGlobalLayoutListener());
  }

  @Override
  public void onViewAdded(View child) {
    super.onViewAdded(child);

    if (mShouldLogContentAppeared) {
      mShouldLogContentAppeared = false;

      if (mJSModuleName != null) {
        ReactMarker.logMarker(ReactMarkerConstants.CONTENT_APPEARED, mJSModuleName, mRootViewTag);
      }
    }
  }

  @Override
  public ViewGroup getRootViewGroup() {
    return this;
  }

  /**
   * {@see #startReactApplication(ReactInstanceManager, String, android.os.Bundle)}
   */
  public void startReactApplication(ReactInstanceManager reactInstanceManager, String moduleName) {
    startReactApplication(reactInstanceManager, moduleName, null);
  }

  /**
   * {@see #startReactApplication(ReactInstanceManager, String, android.os.Bundle, String)}
   */
  public void startReactApplication(ReactInstanceManager reactInstanceManager, String moduleName, @Nullable Bundle initialProperties) {
    startReactApplication(reactInstanceManager, moduleName, initialProperties, null);
  }

  /**
   * Schedule rendering of the react component rendered by the JS application from the given JS
   * module (@{param moduleName}) using provided {@param reactInstanceManager} to attach to the
   * JS context of that manager. Extra parameter {@param launchOptions} can be used to pass initial
   * properties for the react component.
   */
  public void startReactApplication(
      ReactInstanceManager reactInstanceManager,
      String moduleName,
      @Nullable Bundle initialProperties,
      @Nullable String initialUITemplate) {
    Systrace.beginSection(TRACE_TAG_REACT_JAVA_BRIDGE, "startReactApplication");
    // CRN BEGIN
    // 统计开始时间

    mStartTime = System.currentTimeMillis();
    try {
      UiThreadUtil.assertOnUiThread();

      // TODO(6788889): Use POJO instead of bundle here, apparently we can't just use WritableMap
      // here as it may be deallocated in native after passing via JNI bridge, but we want to reuse
      // it in the case of re-creating the catalyst instance
      Assertions.assertCondition(
        mReactInstanceManager == null,
        "This root view has already been attached to a catalyst instance manager");

      mReactInstanceManager = reactInstanceManager;
      mJSModuleName = moduleName;
      mAppProperties = initialProperties;
      mInitialUITemplate = initialUITemplate;
      // CRN BEGIN
      // 解耦RootView 与 ReactInstance, create Background 在加载完之后做

//    if (!mReactInstanceManager.hasStartedCreatingInitialContext()) {
//      mReactInstanceManager.createReactContextInBackground();
//    }

      // CRN END
      if (mUseSurface) {
        // TODO initialize surface here
      }

      if (!mReactInstanceManager.hasStartedCreatingInitialContext()) {
        mReactInstanceManager.createReactContextInBackground();
      }

      attachToReactInstanceManager();

    } finally {
      Systrace.endSection(TRACE_TAG_REACT_JAVA_BRIDGE);
    }
  }

  private void updateRootLayoutSpecs(final int widthMeasureSpec, final int heightMeasureSpec) {
    if (mReactInstanceManager == null) {
      FLog.w(
          ReactConstants.TAG,
          "Unable to update root layout specs for uninitialized ReactInstanceManager");
      return;
    }
    final ReactContext reactApplicationContext = mReactInstanceManager.getCurrentReactContext();

    if (reactApplicationContext != null) {
      UIManagerHelper.getUIManager(reactApplicationContext, getUIManagerType())
        .updateRootLayoutSpecs(getRootViewTag(), widthMeasureSpec, heightMeasureSpec);
    }
  }

  /**
   * Unmount the react application at this root view, reclaiming any JS memory associated with that
   * application. If {@link #startReactApplication} is called, this method must be called before the
   * ReactRootView is garbage collected (typically in your Activity's onDestroy, or in your
   * Fragment's onDestroyView).
   */
  public void unmountReactApplication() {
    if (mReactInstanceManager != null && mIsAttachedToInstance) {
      mReactInstanceManager.detachRootView(this);
      mReactInstanceManager = null;
      mIsAttachedToInstance = false;
    }
    mShouldLogContentAppeared = false;
    // CRN BEGIN
// 变量置空

    if (mDisplayCallback != null) {
      mDisplayCallback = null;
    }

// CRN END
  }

  @Override
  public void onStage(int stage) {
    switch(stage) {
      case ReactStage.ON_ATTACH_TO_INSTANCE:
        onAttachedToReactInstance();
        break;
      default:
        break;
    }
  }

  public void onAttachedToReactInstance() {
    // Create the touch dispatcher here instead of having it always available, to make sure
    // that all touch events are only passed to JS after React/JS side is ready to consume
    // them. Otherwise, these events might break the states expected by JS.
    // Note that this callback was invoked from within the UI thread.
    mJSTouchDispatcher = new JSTouchDispatcher(this);
    if (mRootViewEventListener != null) {
      mRootViewEventListener.onAttachedToReactInstance(this);
    }
  }

  public void setEventListener(ReactRootViewEventListener eventListener) {
    mRootViewEventListener = eventListener;
  }

  /* package */ String getJSModuleName() {
    return Assertions.assertNotNull(mJSModuleName);
  }

  @Override
  public @Nullable Bundle getAppProperties() {
    return mAppProperties;
  }

  @Override
  public @Nullable String getInitialUITemplate() {
    return mInitialUITemplate;
  }

  public void setAppProperties(@Nullable Bundle appProperties) {
    UiThreadUtil.assertOnUiThread();
    mAppProperties = appProperties;
    if (getRootViewTag() != 0) {
      runApplication();
    }
  }

  /**
   * Calls into JS to start the React application. Can be called multiple times with the
   * same rootTag, which will re-render the application from the root.
   */
  @Override
  public void runApplication() {
      Systrace.beginSection(TRACE_TAG_REACT_JAVA_BRIDGE, "ReactRootView.runApplication");
      try {
        if (mReactInstanceManager == null || !mIsAttachedToInstance) {
          return;
        }

        ReactContext reactContext = mReactInstanceManager.getCurrentReactContext();
        if (reactContext == null) {
          return;
        }

        CatalystInstance catalystInstance = reactContext.getCatalystInstance();
        String jsAppModuleName = getJSModuleName();

        if (mUseSurface) {
          // TODO call surface's runApplication
        } else {

          boolean isFabric = getUIManagerType() == FABRIC;
          // Fabric requires to call updateRootLayoutSpecs before starting JS Application,
          // this ensures the root will hace the correct pointScaleFactor.
          if (mWasMeasured || isFabric) {
            updateRootLayoutSpecs(mWidthMeasureSpec, mHeightMeasureSpec);
          }

          WritableNativeMap appParams = new WritableNativeMap();
          appParams.putDouble("rootTag", getRootViewTag());
          @Nullable Bundle appProperties = getAppProperties();
          if (appProperties != null) {
            appParams.putMap("initialProps", Arguments.fromBundle(appProperties));
          }
          if (isFabric) {
            appParams.putBoolean("fabric", true);
          }

          mShouldLogContentAppeared = true;

          catalystInstance.getJSModule(AppRegistry.class).runApplication(jsAppModuleName, appParams);
        }
      } finally {
        Systrace.endSection(TRACE_TAG_REACT_JAVA_BRIDGE);
      }
  }

  /**
   * Is used by unit test to setup mIsAttachedToWindow flags, that will let this
   * view to be properly attached to catalyst instance by startReactApplication call
   */
  @VisibleForTesting
  /* package */ void simulateAttachForTesting() {
    mIsAttachedToInstance = true;
    mJSTouchDispatcher = new JSTouchDispatcher(this);
  }

  private CustomGlobalLayoutListener getCustomGlobalLayoutListener() {
    if (mCustomGlobalLayoutListener == null) {
      mCustomGlobalLayoutListener = new CustomGlobalLayoutListener();
    }
    return mCustomGlobalLayoutListener;
  }

  private void attachToReactInstanceManager() {
    Systrace.beginSection(TRACE_TAG_REACT_JAVA_BRIDGE, "attachToReactInstanceManager");
    try {
      if (mIsAttachedToInstance) {
        return;
      }

      mIsAttachedToInstance = true;
      Assertions.assertNotNull(mReactInstanceManager).attachRootView(this);
      getViewTreeObserver().addOnGlobalLayoutListener(getCustomGlobalLayoutListener());
    } finally {
      Systrace.endSection(TRACE_TAG_REACT_JAVA_BRIDGE);
    }
  }

  @Override
  protected void finalize() throws Throwable {
    super.finalize();
    Assertions.assertCondition(
      !mIsAttachedToInstance,
      "The application this ReactRootView was rendering was not unmounted before the " +
        "ReactRootView was garbage collected. This usually means that your application is " +
        "leaking large amounts of memory. To solve this, make sure to call " +
        "ReactRootView#unmountReactApplication in the onDestroy() of your hosting Activity or in " +
        "the onDestroyView() of your hosting Fragment.");
  }

  public int getRootViewTag() {
    return mRootViewTag;
  }

  public void setRootViewTag(int rootViewTag) {
    mRootViewTag = rootViewTag;
  }

  @Override
  public void handleException(final Throwable t) {
    if (mReactInstanceManager == null
      || mReactInstanceManager.getCurrentReactContext() == null) {
        throw new RuntimeException(t);
    }

    Exception e = new IllegalViewOperationException(t.getMessage(), this, t);
    mReactInstanceManager.getCurrentReactContext().handleException(e);
  }

  public void setIsFabric(boolean isFabric) {
    mUIManagerType = isFabric ? FABRIC : DEFAULT;
  }

  @Override
  public @UIManagerType int getUIManagerType() {
    return mUIManagerType;
  }

  @Nullable
  public ReactInstanceManager getReactInstanceManager() {
    return mReactInstanceManager;
  }

  /* package */ void sendEvent(String eventName, @Nullable WritableMap params) {
    if (mReactInstanceManager != null) {
      mReactInstanceManager.getCurrentReactContext()
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
        .emit(eventName, params);
    }
  }

  private class CustomGlobalLayoutListener implements ViewTreeObserver.OnGlobalLayoutListener {
    private final Rect mVisibleViewArea;
    private final int mMinKeyboardHeightDetected;

    private int mKeyboardHeight = 0;
    private int mDeviceRotation = 0;
    private DisplayMetrics mWindowMetrics = new DisplayMetrics();
    private DisplayMetrics mScreenMetrics = new DisplayMetrics();

    /* package */ CustomGlobalLayoutListener() {
      DisplayMetricsHolder.initDisplayMetricsIfNotInitialized(getContext().getApplicationContext());
      mVisibleViewArea = new Rect();
      mMinKeyboardHeightDetected = (int) PixelUtil.toPixelFromDIP(60);
    }

    @Override
    public void onGlobalLayout() {
      if (mReactInstanceManager == null || !mIsAttachedToInstance ||
        mReactInstanceManager.getCurrentReactContext() == null) {
        return;
      }
      checkForKeyboardEvents();
      checkForDeviceOrientationChanges();
      checkForDeviceDimensionsChanges();
    }

    private void checkForKeyboardEvents() {
      getRootView().getWindowVisibleDisplayFrame(mVisibleViewArea);
      final int heightDiff =
        DisplayMetricsHolder.getWindowDisplayMetrics().heightPixels - mVisibleViewArea.bottom;
      if (mKeyboardHeight != heightDiff && heightDiff > mMinKeyboardHeightDetected) {
        // keyboard is now showing, or the keyboard height has changed
        mKeyboardHeight = heightDiff;
        WritableMap params = Arguments.createMap();
        WritableMap coordinates = Arguments.createMap();
        coordinates.putDouble("screenY", PixelUtil.toDIPFromPixel(mVisibleViewArea.bottom));
        coordinates.putDouble("screenX", PixelUtil.toDIPFromPixel(mVisibleViewArea.left));
        coordinates.putDouble("width", PixelUtil.toDIPFromPixel(mVisibleViewArea.width()));
        coordinates.putDouble("height", PixelUtil.toDIPFromPixel(mKeyboardHeight));
        params.putMap("endCoordinates", coordinates);
        sendEvent("keyboardDidShow", params);
      } else if (mKeyboardHeight != 0 && heightDiff <= mMinKeyboardHeightDetected) {
        // keyboard is now hidden
        mKeyboardHeight = 0;
        sendEvent("keyboardDidHide", null);
      }
    }

    private void checkForDeviceOrientationChanges() {
      final int rotation =
        ((WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE))
          .getDefaultDisplay().getRotation();
      if (mDeviceRotation == rotation) {
        return;
      }
      mDeviceRotation = rotation;
      emitOrientationChanged(rotation);
    }

    private void checkForDeviceDimensionsChanges() {
      // Get current display metrics.
      DisplayMetricsHolder.initDisplayMetrics(getContext());
      // Check changes to both window and screen display metrics since they may not update at the same time.
      if (!areMetricsEqual(mWindowMetrics, DisplayMetricsHolder.getWindowDisplayMetrics()) ||
        !areMetricsEqual(mScreenMetrics, DisplayMetricsHolder.getScreenDisplayMetrics())) {
        mWindowMetrics.setTo(DisplayMetricsHolder.getWindowDisplayMetrics());
        mScreenMetrics.setTo(DisplayMetricsHolder.getScreenDisplayMetrics());
        emitUpdateDimensionsEvent();
      }
    }

    private boolean areMetricsEqual(DisplayMetrics displayMetrics, DisplayMetrics otherMetrics) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
        return displayMetrics.equals(otherMetrics);
      } else {
        // DisplayMetrics didn't have an equals method before API 17.
        // Check all public fields manually.
        return displayMetrics.widthPixels == otherMetrics.widthPixels &&
          displayMetrics.heightPixels == otherMetrics.heightPixels &&
          displayMetrics.density == otherMetrics.density &&
          displayMetrics.densityDpi == otherMetrics.densityDpi &&
          displayMetrics.scaledDensity == otherMetrics.scaledDensity &&
          displayMetrics.xdpi == otherMetrics.xdpi &&
          displayMetrics.ydpi == otherMetrics.ydpi;
      }
    }

    private void emitOrientationChanged(final int newRotation) {
      String name;
      double rotationDegrees;
      boolean isLandscape = false;

      switch (newRotation) {
        case Surface.ROTATION_0:
          name = "portrait-primary";
          rotationDegrees = 0.0;
          break;
        case Surface.ROTATION_90:
          name = "landscape-primary";
          rotationDegrees = -90.0;
          isLandscape = true;
          break;
        case Surface.ROTATION_180:
          name = "portrait-secondary";
          rotationDegrees = 180.0;
          break;
        case Surface.ROTATION_270:
          name = "landscape-secondary";
          rotationDegrees = 90.0;
          isLandscape = true;
          break;
        default:
          return;
      }
      WritableMap map = Arguments.createMap();
      map.putString("name", name);
      map.putDouble("rotationDegrees", rotationDegrees);
      map.putBoolean("isLandscape", isLandscape);

      sendEvent("namedOrientationDidChange", map);
    }

    private void emitUpdateDimensionsEvent() {
      mReactInstanceManager
          .getCurrentReactContext()
          .getNativeModule(DeviceInfoModule.class)
          .emitUpdateDimensionsEvent();
    }
  }
}
