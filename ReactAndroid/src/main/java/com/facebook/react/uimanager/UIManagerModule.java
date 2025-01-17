/**
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * <p>This source code is licensed under the MIT license found in the LICENSE file in the root
 * directory of this source tree.
 */
package com.facebook.react.uimanager;

import static com.facebook.react.bridge.ReactMarkerConstants.CREATE_UI_MANAGER_MODULE_CONSTANTS_END;
import static com.facebook.react.bridge.ReactMarkerConstants.CREATE_UI_MANAGER_MODULE_CONSTANTS_START;
import static com.facebook.react.uimanager.common.UIManagerType.DEFAULT;
import static com.facebook.react.uimanager.common.UIManagerType.FABRIC;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.view.View;
import android.support.v4.util.ArrayMap;
import com.facebook.common.logging.FLog;
import com.facebook.debug.holder.PrinterHolder;
import com.facebook.debug.tags.ReactDebugOverlayTags;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.GuardedRunnable;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.OnBatchCompleteListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMarker;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.UIManager;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.common.annotations.VisibleForTesting;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.uimanager.common.ViewUtil;
import com.facebook.react.uimanager.debug.NotThreadSafeViewHierarchyUpdateDebugListener;
import com.facebook.react.uimanager.events.EventDispatcher;
import com.facebook.react.uimanager.events.RCTEventEmitter;
import com.facebook.systrace.Systrace;
import com.facebook.systrace.SystraceMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

import ctrip.crn.CRNUIEventListener;

/**
 * Native module to allow JS to create and update native Views.
 *
 * <p>
 *
 * <h2>== Transactional Requirement ==</h2>
 *
 * A requirement of this class is to make sure that transactional UI updates occur all at once,
 * meaning that no intermediate state is ever rendered to the screen. For example, if a JS
 * application update changes the background of View A to blue and the width of View B to 100, both
 * need to appear at once. Practically, this means that all UI update code related to a single
 * transaction must be executed as a single code block on the UI thread. Executing as multiple code
 * blocks could allow the platform UI system to interrupt and render a partial UI state.
 *
 * <p>To facilitate this, this module enqueues operations that are then applied to native view
 * hierarchy through {@link NativeViewHierarchyManager} at the end of each transaction.
 *
 * <p>
 *
 * <h2>== CSSNodes ==</h2>
 *
 * In order to allow layout and measurement to occur on a non-UI thread, this module also operates
 * on intermediate CSSNodeDEPRECATED objects that correspond to a native view. These
 * CSSNodeDEPRECATED are able to calculate layout according to their styling rules, and then the
 * resulting x/y/width/height of that layout is scheduled as an operation that will be applied to
 * native view hierarchy at the end of current batch. TODO(5241856): Investigate memory usage of
 * creating many small objects in UIManageModule and consider implementing a pool TODO(5483063):
 * Don't dispatch the view hierarchy at the end of a batch if no UI changes occurred
 */
@ReactModule(name = UIManagerModule.NAME)
public class UIManagerModule extends ReactContextBaseJavaModule
    implements OnBatchCompleteListener, LifecycleEventListener, UIManager {

  /** Enables lazy discovery of a specific {@link ViewManager} by its name. */
  public interface ViewManagerResolver {
    /**
     * {@class UIManagerModule} class uses this method to get a ViewManager by its name. This is the
     * same name that comes from JS by {@code UIManager.ViewManagerName} call.
     */
    @Nullable
    ViewManager getViewManager(String viewManagerName);

    /**
     * Provides a list of view manager names to register in JS as {@code UIManager.ViewManagerName}
     */
    List<String> getViewManagerNames();
  }

  /** Resolves a name coming from native side to a name of the event that is exposed to JS. */
  public interface CustomEventNamesResolver {
    /** Returns custom event name by the provided event name. */
    @Nullable
    String resolveCustomEventName(String eventName);
  }

  public static final String NAME = "UIManager";

  private static final boolean DEBUG =
      PrinterHolder.getPrinter().shouldDisplayLogMessage(ReactDebugOverlayTags.UI_MANAGER);

  private final EventDispatcher mEventDispatcher;
  private final Map<String, Object> mModuleConstants;
  private final Map<String, Object> mCustomDirectEvents;
  private final ViewManagerRegistry mViewManagerRegistry;
  private final UIImplementation mUIImplementation;
  private final MemoryTrimCallback mMemoryTrimCallback = new MemoryTrimCallback();
  private final List<UIManagerModuleListener> mListeners = new ArrayList<>();
  private @Nullable Map<String, WritableMap> mViewManagerConstantsCache;
  private volatile int mViewManagerConstantsCacheSize;

  private int mBatchId = 0;

  @SuppressWarnings("deprecated")
  public UIManagerModule(
      ReactApplicationContext reactContext,
      ViewManagerResolver viewManagerResolver,
      int minTimeLeftInFrameForNonBatchedOperationMs) {
    this(
        reactContext,
        viewManagerResolver,
        new UIImplementationProvider(),
        minTimeLeftInFrameForNonBatchedOperationMs);
  }

  @SuppressWarnings("deprecated")
  public UIManagerModule(
      ReactApplicationContext reactContext,
      List<ViewManager> viewManagersList,
      int minTimeLeftInFrameForNonBatchedOperationMs) {
    this(
        reactContext,
        viewManagersList,
        new UIImplementationProvider(),
        minTimeLeftInFrameForNonBatchedOperationMs);
  }

  @Deprecated
  public UIManagerModule(
      ReactApplicationContext reactContext,
      ViewManagerResolver viewManagerResolver,
      UIImplementationProvider uiImplementationProvider,
      int minTimeLeftInFrameForNonBatchedOperationMs) {
    super(reactContext);
    DisplayMetricsHolder.initDisplayMetricsIfNotInitialized(reactContext);
    mEventDispatcher = new EventDispatcher(reactContext);
    mModuleConstants = createConstants(viewManagerResolver);
    mCustomDirectEvents = UIManagerModuleConstants.getDirectEventTypeConstants();
    mViewManagerRegistry = new ViewManagerRegistry(viewManagerResolver);
    mUIImplementation =
        uiImplementationProvider.createUIImplementation(
            reactContext,
            mViewManagerRegistry,
            mEventDispatcher,
            minTimeLeftInFrameForNonBatchedOperationMs);

    reactContext.addLifecycleEventListener(this);
  }

  @Deprecated
  public UIManagerModule(
      ReactApplicationContext reactContext,
      List<ViewManager> viewManagersList,
      UIImplementationProvider uiImplementationProvider,
      int minTimeLeftInFrameForNonBatchedOperationMs) {
    super(reactContext);
    DisplayMetricsHolder.initDisplayMetricsIfNotInitialized(reactContext);
    mEventDispatcher = new EventDispatcher(reactContext);
    mCustomDirectEvents = MapBuilder.newHashMap();
    mModuleConstants = createConstants(viewManagersList, null, mCustomDirectEvents);
    mViewManagerRegistry = new ViewManagerRegistry(viewManagersList);
    mUIImplementation =
        uiImplementationProvider.createUIImplementation(
            reactContext,
            mViewManagerRegistry,
            mEventDispatcher,
            minTimeLeftInFrameForNonBatchedOperationMs);

    reactContext.addLifecycleEventListener(this);
  }

  /**
   * This method gives an access to the {@link UIImplementation} object that can be used to execute
   * operations on the view hierarchy.
   */
  public UIImplementation getUIImplementation() {
    return mUIImplementation;
  }
  //CRN BEGIN
  /**
   * @param eventListener
   * @param postEventWhenUIChangedFlag  0, createView; 1 updateView
   */
  public void setUIRenderEventListener(CRNUIEventListener eventListener, boolean postEventWhenUIChangedFlag) {
    if (mUIImplementation != null) {
      mUIImplementation.setUIRenderEventListener(eventListener, postEventWhenUIChangedFlag);
    }
  }
  //CRN END
  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public Map<String, Object> getConstants() {
    return mModuleConstants;
  }

  @Override
  public void initialize() {
    getReactApplicationContext().registerComponentCallbacks(mMemoryTrimCallback);
    mEventDispatcher.registerEventEmitter(
        DEFAULT, getReactApplicationContext().getJSModule(RCTEventEmitter.class));
  }

  @Override
  public void onHostResume() {
    mUIImplementation.onHostResume();
  }

  @Override
  public void onHostPause() {
    mUIImplementation.onHostPause();
  }

  @Override
  public void onHostDestroy() {
    mUIImplementation.onHostDestroy();
  }

  @Override
  public void onCatalystInstanceDestroy() {
    super.onCatalystInstanceDestroy();
    mEventDispatcher.onCatalystInstanceDestroyed();

    getReactApplicationContext().unregisterComponentCallbacks(mMemoryTrimCallback);
    YogaNodePool.get().clear();
    ViewManagerPropertyUpdater.clear();
  }

  /**
   * This method is intended to reuse the {@link ViewManagerRegistry} with FabricUIManager.
   * Do not use this method as this will be removed in the near future.
   */
  @Deprecated
  public ViewManagerRegistry getViewManagerRegistry_DO_NOT_USE() {
    return mViewManagerRegistry;
  }

  private static Map<String, Object> createConstants(ViewManagerResolver viewManagerResolver) {
    ReactMarker.logMarker(CREATE_UI_MANAGER_MODULE_CONSTANTS_START);
    SystraceMessage.beginSection(Systrace.TRACE_TAG_REACT_JAVA_BRIDGE, "CreateUIManagerConstants")
        .arg("Lazy", true)
        .flush();
    try {
      return UIManagerModuleConstantsHelper.createConstants(viewManagerResolver);
    } finally {
      Systrace.endSection(Systrace.TRACE_TAG_REACT_JAVA_BRIDGE);
      ReactMarker.logMarker(CREATE_UI_MANAGER_MODULE_CONSTANTS_END);
    }
  }

  private static Map<String, Object> createConstants(
      List<ViewManager> viewManagers,
      @Nullable Map<String, Object> customBubblingEvents,
      @Nullable Map<String, Object> customDirectEvents) {
    ReactMarker.logMarker(CREATE_UI_MANAGER_MODULE_CONSTANTS_START);
    SystraceMessage.beginSection(Systrace.TRACE_TAG_REACT_JAVA_BRIDGE, "CreateUIManagerConstants")
        .arg("Lazy", false)
        .flush();
    try {
      return UIManagerModuleConstantsHelper.createConstants(
          viewManagers, customBubblingEvents, customDirectEvents);
    } finally {
      Systrace.endSection(Systrace.TRACE_TAG_REACT_JAVA_BRIDGE);
      ReactMarker.logMarker(CREATE_UI_MANAGER_MODULE_CONSTANTS_END);
    }
  }

  /**
   * Helper method to pre-compute the constants for a view manager. This method ensures that we
   * don't block for getting the constants for view managers during TTI
   *
   * @param viewManagerNames
   */
  @Deprecated
  public void preComputeConstantsForViewManager(List<String> viewManagerNames) {
    Map<String, WritableMap> constantsMap = new ArrayMap<>();
    for (String viewManagerName : viewManagerNames) {
      WritableMap constants = computeConstantsForViewManager(viewManagerName);
      if (constants != null) {
        constantsMap.put(viewManagerName, constants);
      }
    }

    // To ensure that this is thread safe, we return an unmodifiableMap
    // We use mViewManagerConstantsCacheSize to count the times we access the contents of the map
    // Once we have accessed all the values, we free this cache
    // Assumption is that JS gets the constants only once for each viewManager.
    // Using this mechanism prevents expensive synchronized blocks, due to the nature of how this is
    // accessed - write one, read multiple times, and then throw the data away.
    mViewManagerConstantsCacheSize = viewManagerNames.size();
    mViewManagerConstantsCache = Collections.unmodifiableMap(constantsMap);
  }

  @ReactMethod(isBlockingSynchronousMethod = true)
  public @Nullable WritableMap getConstantsForViewManager(final String viewManagerName) {
    WritableMap writableMap = null;
    if (mViewManagerConstantsCache != null
        && mViewManagerConstantsCache.containsKey(viewManagerName)) {
      WritableMap constants = mViewManagerConstantsCache.get(viewManagerName);
      if (--mViewManagerConstantsCacheSize <= 0) {
        // Looks like we have read all the values from the cache, so we may as well free this cache
        mViewManagerConstantsCache = null;
      }
      writableMap = constants;
    } else {
      writableMap = computeConstantsForViewManager(viewManagerName);
    }
    if (writableMap == null) {
      return new WritableNativeMap();
    }
    return writableMap;
  }

  private @Nullable WritableMap computeConstantsForViewManager(final String viewManagerName) {
    ViewManager targetView =
        viewManagerName != null ? mUIImplementation.resolveViewManager(viewManagerName) : null;
    if (targetView == null) {
      return null;
    }

    SystraceMessage.beginSection(
            Systrace.TRACE_TAG_REACT_JAVA_BRIDGE, "UIManagerModule.getConstantsForViewManager")
        .arg("ViewManager", targetView.getName())
        .arg("Lazy", true)
        .flush();
    try {
      Map<String, Object> viewManagerConstants =
          UIManagerModuleConstantsHelper.createConstantsForViewManager(
              targetView, null, null, null, mCustomDirectEvents);
      if (viewManagerConstants != null) {
        return Arguments.makeNativeMap(viewManagerConstants);
      }
      return null;
    } finally {
      SystraceMessage.endSection(Systrace.TRACE_TAG_REACT_JAVA_BRIDGE).flush();
    }
  }

  @ReactMethod(isBlockingSynchronousMethod = true)
  public WritableMap getDefaultEventTypes() {
    return Arguments.makeNativeMap(UIManagerModuleConstantsHelper.getDefaultExportableEventTypes());
  }

  /** Resolves Direct Event name exposed to JS from the one known to the Native side. */
  public CustomEventNamesResolver getDirectEventNamesResolver() {
    return new CustomEventNamesResolver() {
      @Override
      public @Nullable String resolveCustomEventName(String eventName) {
        Map<String, String> customEventType =
            (Map<String, String>) mCustomDirectEvents.get(eventName);
        if (customEventType != null) {
          return customEventType.get("registrationName");
        }
        return eventName;
      }
    };
  }

  @Override
  public void profileNextBatch() {
    mUIImplementation.profileNextBatch();
  }

  @Override
  public Map<String, Long> getPerformanceCounters() {
    return mUIImplementation.getProfiledBatchPerfCounters();
  }

  public <T extends View> int addRootView(
      final T rootView) {
        return addRootView(rootView, null, null);
      }

  /**
   * Used by native animated module to bypass the process of updating the values through the shadow
   * view hierarchy. This method will directly update native views, which means that updates for
   * layout-related propertied won't be handled properly.
   * Make sure you know what you're doing before calling this method :)
   */
  @Override
  public void synchronouslyUpdateViewOnUIThread(int tag, ReadableMap props) {
    int uiManagerType = ViewUtil.getUIManagerType(tag);
    if (uiManagerType == FABRIC) {
      UIManager fabricUIManager = UIManagerHelper.getUIManager(getReactApplicationContext(), uiManagerType);
      fabricUIManager.synchronouslyUpdateViewOnUIThread(tag, props);
    } else {
      mUIImplementation.synchronouslyUpdateViewOnUIThread(tag, new ReactStylesDiffMap(props));
    }
  }

  /**
   * Registers a new root view. JS can use the returned tag with manageChildren to add/remove
   * children to this view.
   *
   * <p>Note that this must be called after getWidth()/getHeight() actually return something. See
   * CatalystApplicationFragment as an example.
   *
   * <p>TODO(6242243): Make addRootView thread safe NB: this method is horribly not-thread-safe.
   */
  @Override
  public <T extends View> int addRootView(
      final T rootView, WritableMap initialProps, @Nullable String initialUITemplate) {
    Systrace.beginSection(Systrace.TRACE_TAG_REACT_JAVA_BRIDGE, "UIManagerModule.addRootView");
    final int tag = ReactRootViewTagGenerator.getNextRootViewTag();
    final ReactApplicationContext reactApplicationContext = getReactApplicationContext();
    final ThemedReactContext themedRootContext =
        new ThemedReactContext(reactApplicationContext, rootView.getContext());

    mUIImplementation.registerRootView(rootView, tag, themedRootContext);
    Systrace.endSection(Systrace.TRACE_TAG_REACT_JAVA_BRIDGE);
    return tag;
  }

  @ReactMethod
  public void removeRootView(int rootViewTag) {
    mUIImplementation.removeRootView(rootViewTag);
  }

  public void updateNodeSize(int nodeViewTag, int newWidth, int newHeight) {
    getReactApplicationContext().assertOnNativeModulesQueueThread();

    mUIImplementation.updateNodeSize(nodeViewTag, newWidth, newHeight);
  }

  /**
   * Sets local data for a shadow node corresponded with given tag. In some cases we need a way to
   * specify some environmental data to shadow node to improve layout (or do something similar), so
   * {@code localData} serves these needs. For example, any stateful embedded native views may
   * benefit from this. Have in mind that this data is not supposed to interfere with the state of
   * the shadow view. Please respect one-directional data flow of React.
   */
  public void setViewLocalData(final int tag, final Object data) {
    final ReactApplicationContext reactApplicationContext = getReactApplicationContext();

    reactApplicationContext.assertOnUiQueueThread();

    reactApplicationContext.runOnNativeModulesQueueThread(
        new GuardedRunnable(reactApplicationContext) {
          @Override
          public void runGuarded() {
            mUIImplementation.setViewLocalData(tag, data);
          }
        });
  }

  @ReactMethod
  public void createView(int tag, String className, int rootViewTag, ReadableMap props) {
    if (DEBUG) {
      String message =
          "(UIManager.createView) tag: " + tag + ", class: " + className + ", props: " + props;
      FLog.d(ReactConstants.TAG, message);
      PrinterHolder.getPrinter().logMessage(ReactDebugOverlayTags.UI_MANAGER, message);
    }
    mUIImplementation.createView(tag, className, rootViewTag, props);
  }

  @ReactMethod
  public void updateView(int tag, String className, ReadableMap props) {
    if (DEBUG) {
      String message =
          "(UIManager.updateView) tag: " + tag + ", class: " + className + ", props: " + props;
      FLog.d(ReactConstants.TAG, message);
      PrinterHolder.getPrinter().logMessage(ReactDebugOverlayTags.UI_MANAGER, message);
    }
    int uiManagerType = ViewUtil.getUIManagerType(tag);
    if (uiManagerType == FABRIC) {
      UIManager fabricUIManager = UIManagerHelper.getUIManager(getReactApplicationContext(), uiManagerType);
      fabricUIManager.synchronouslyUpdateViewOnUIThread(tag, props);
    } else {
      mUIImplementation.updateView(tag, className, props);
    }
  }

  /**
   * Interface for adding/removing/moving views within a parent view from JS.
   *
   * @param viewTag the view tag of the parent view
   * @param moveFrom a list of indices in the parent view to move views from
   * @param moveTo parallel to moveFrom, a list of indices in the parent view to move views to
   * @param addChildTags a list of tags of views to add to the parent
   * @param addAtIndices parallel to addChildTags, a list of indices to insert those children at
   * @param removeFrom a list of indices of views to permanently remove. The memory for the
   *     corresponding views and data structures should be reclaimed.
   */
  @ReactMethod
  public void manageChildren(
      int viewTag,
      @Nullable ReadableArray moveFrom,
      @Nullable ReadableArray moveTo,
      @Nullable ReadableArray addChildTags,
      @Nullable ReadableArray addAtIndices,
      @Nullable ReadableArray removeFrom) {
    if (DEBUG) {
      String message =
          "(UIManager.manageChildren) tag: "
              + viewTag
              + ", moveFrom: "
              + moveFrom
              + ", moveTo: "
              + moveTo
              + ", addTags: "
              + addChildTags
              + ", atIndices: "
              + addAtIndices
              + ", removeFrom: "
              + removeFrom;
      FLog.d(ReactConstants.TAG, message);
      PrinterHolder.getPrinter().logMessage(ReactDebugOverlayTags.UI_MANAGER, message);
    }
    mUIImplementation.manageChildren(
        viewTag, moveFrom, moveTo, addChildTags, addAtIndices, removeFrom);
  }

  /**
   * Interface for fast tracking the initial adding of views. Children view tags are assumed to be
   * in order
   *
   * @param viewTag the view tag of the parent view
   * @param childrenTags An array of tags to add to the parent in order
   */
  @ReactMethod
  public void setChildren(int viewTag, ReadableArray childrenTags) {
    if (DEBUG) {
      String message = "(UIManager.setChildren) tag: " + viewTag + ", children: " + childrenTags;
      FLog.d(ReactConstants.TAG, message);
      PrinterHolder.getPrinter().logMessage(ReactDebugOverlayTags.UI_MANAGER, message);
    }
    mUIImplementation.setChildren(viewTag, childrenTags);
  }

  /**
   * Replaces the View specified by oldTag with the View specified by newTag within oldTag's parent.
   * This resolves to a simple {@link #manageChildren} call, but React doesn't have enough info in
   * JS to formulate it itself.
   */
  @ReactMethod
  public void replaceExistingNonRootView(int oldTag, int newTag) {
    mUIImplementation.replaceExistingNonRootView(oldTag, newTag);
  }

  /**
   * Method which takes a container tag and then releases all subviews for that container upon
   * receipt. TODO: The method name is incorrect and will be renamed, #6033872
   *
   * @param containerTag the tag of the container for which the subviews must be removed
   */
  @ReactMethod
  public void removeSubviewsFromContainerWithID(int containerTag) {
    mUIImplementation.removeSubviewsFromContainerWithID(containerTag);
  }

  /**
   * Determines the location on screen, width, and height of the given view and returns the values
   * via an async callback.
   */
  @ReactMethod
  public void measure(int reactTag, Callback callback) {
    mUIImplementation.measure(reactTag, callback);
  }

  /**
   * Determines the location on screen, width, and height of the given view relative to the device
   * screen and returns the values via an async callback. This is the absolute position including
   * things like the status bar
   */
  @ReactMethod
  public void measureInWindow(int reactTag, Callback callback) {
    mUIImplementation.measureInWindow(reactTag, callback);
  }

  /**
   * Measures the view specified by tag relative to the given ancestorTag. This means that the
   * returned x, y are relative to the origin x, y of the ancestor view. Results are stored in the
   * given outputBuffer. We allow ancestor view and measured view to be the same, in which case the
   * position always will be (0, 0) and method will only measure the view dimensions.
   *
   * <p>NB: Unlike {@link #measure}, this will measure relative to the view layout, not the visible
   * window which can cause unexpected results when measuring relative to things like ScrollViews
   * that can have offset content on the screen.
   */
  @ReactMethod
  public void measureLayout(
      int tag, int ancestorTag, Callback errorCallback, Callback successCallback) {
    mUIImplementation.measureLayout(tag, ancestorTag, errorCallback, successCallback);
  }

  /**
   * Like {@link #measure} and {@link #measureLayout} but measures relative to the immediate parent.
   *
   * <p>NB: Unlike {@link #measure}, this will measure relative to the view layout, not the visible
   * window which can cause unexpected results when measuring relative to things like ScrollViews
   * that can have offset content on the screen.
   */
  @ReactMethod
  public void measureLayoutRelativeToParent(
      int tag, Callback errorCallback, Callback successCallback) {
    mUIImplementation.measureLayoutRelativeToParent(tag, errorCallback, successCallback);
  }

  /**
   * Find the touch target child native view in the supplied root view hierarchy, given a react
   * target location.
   *
   * <p>This method is currently used only by Element Inspector DevTool.
   *
   * @param reactTag the tag of the root view to traverse
   * @param point an array containing both X and Y target location
   * @param callback will be called if with the identified child view react ID, and measurement
   *     info. If no view was found, callback will be invoked with no data.
   */
  @ReactMethod
  public void findSubviewIn(
      final int reactTag, final ReadableArray point, final Callback callback) {
    mUIImplementation.findSubviewIn(
        reactTag,
        Math.round(PixelUtil.toPixelFromDIP(point.getDouble(0))),
        Math.round(PixelUtil.toPixelFromDIP(point.getDouble(1))),
        callback);
  }

  /** Check if the first shadow node is the descendant of the second shadow node */
  @ReactMethod
  public void viewIsDescendantOf(
      final int reactTag, final int ancestorReactTag, final Callback callback) {
    mUIImplementation.viewIsDescendantOf(reactTag, ancestorReactTag, callback);
  }

  @Override
  @ReactMethod
  public void setJSResponder(int reactTag, boolean blockNativeResponder) {
    mUIImplementation.setJSResponder(reactTag, blockNativeResponder);
  }

  @Override
  @ReactMethod
  public void clearJSResponder() {
    mUIImplementation.clearJSResponder();
  }

  @ReactMethod
  public void dispatchViewManagerCommand(
      int reactTag, int commandId, @Nullable ReadableArray commandArgs) {
    // TODO: this is a temporary approach to support ViewManagerCommands in Fabric until
    // the dispatchViewManagerCommand() method is supported by Fabric JS API.
    UIManagerHelper.getUIManager(getReactApplicationContext(), ViewUtil.getUIManagerType(reactTag))
        .dispatchCommand(reactTag, commandId, commandArgs);
  }

  @Override
  public void dispatchCommand(int reactTag, int commandId, @Nullable ReadableArray commandArgs) {
    mUIImplementation.dispatchViewManagerCommand(reactTag, commandId, commandArgs);
  }

  @ReactMethod
  public void playTouchSound() {
    AudioManager audioManager =
        (AudioManager) getReactApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    if (audioManager != null) {
      audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK);
    }
  }

  /**
   * Show a PopupMenu.
   *
   * @param reactTag the tag of the anchor view (the PopupMenu is displayed next to this view); this
   *     needs to be the tag of a native view (shadow views can not be anchors)
   * @param items the menu items as an array of strings
   * @param error will be called if there is an error displaying the menu
   * @param success will be called with the position of the selected item as the first argument, or
   *     no arguments if the menu is dismissed
   */
  @ReactMethod
  public void showPopupMenu(int reactTag, ReadableArray items, Callback error, Callback success) {
    mUIImplementation.showPopupMenu(reactTag, items, error, success);
  }

  @ReactMethod
  public void dismissPopupMenu() {
    mUIImplementation.dismissPopupMenu();
  }

  /**
   * LayoutAnimation API on Android is currently experimental. Therefore, it needs to be enabled
   * explicitly in order to avoid regression in existing application written for iOS using this API.
   *
   * <p>Warning : This method will be removed in future version of React Native, and layout
   * animation will be enabled by default, so always check for its existence before invoking it.
   *
   * <p>TODO(9139831) : remove this method once layout animation is fully stable.
   *
   * @param enabled whether layout animation is enabled or not
   */
  @ReactMethod
  public void setLayoutAnimationEnabledExperimental(boolean enabled) {
    mUIImplementation.setLayoutAnimationEnabledExperimental(enabled);
  }

  /**
   * Configure an animation to be used for the native layout changes, and native views creation. The
   * animation will only apply during the current batch operations.
   *
   * <p>TODO(7728153) : animating view deletion is currently not supported.
   *
   * @param config the configuration of the animation for view addition/removal/update.
   * @param success will be called when the animation completes, or when the animation get
   *     interrupted. In this case, callback parameter will be false.
   * @param error will be called if there was an error processing the animation
   */
  @ReactMethod
  public void configureNextLayoutAnimation(ReadableMap config, Callback success, Callback error) {
    mUIImplementation.configureNextLayoutAnimation(config, success);
  }

  /**
   * To implement the transactional requirement mentioned in the class javadoc, we only commit UI
   * changes to the actual view hierarchy once a batch of JS->Java calls have been completed. We
   * know this is safe because all JS->Java calls that are triggered by a Java->JS call (e.g. the
   * delivery of a touch event or execution of 'renderApplication') end up in a single JS->Java
   * transaction.
   *
   * <p>A better way to do this would be to have JS explicitly signal to this module when a UI
   * transaction is done. Right now, though, this is how iOS does it, and we should probably update
   * the JS and native code and make this change at the same time.
   *
   * <p>TODO(5279396): Make JS UI library explicitly notify the native UI module of the end of a UI
   * transaction using a standard native call
   */
  @Override
  public void onBatchComplete() {
    int batchId = mBatchId;
    mBatchId++;

    SystraceMessage.beginSection(Systrace.TRACE_TAG_REACT_JAVA_BRIDGE, "onBatchCompleteUI")
        .arg("BatchId", batchId)
        .flush();
    for (UIManagerModuleListener listener : mListeners) {
      listener.willDispatchViewUpdates(this);
    }
    try {
      mUIImplementation.dispatchViewUpdates(batchId);
    } finally {
      Systrace.endSection(Systrace.TRACE_TAG_REACT_JAVA_BRIDGE);
    }
  }

  public void setViewHierarchyUpdateDebugListener(
      @Nullable NotThreadSafeViewHierarchyUpdateDebugListener listener) {
    mUIImplementation.setViewHierarchyUpdateDebugListener(listener);
  }

  public EventDispatcher getEventDispatcher() {
    return mEventDispatcher;
  }

  @ReactMethod
  public void sendAccessibilityEvent(int tag, int eventType) {
    mUIImplementation.sendAccessibilityEvent(tag, eventType);
  }

  /**
   * Schedule a block to be executed on the UI thread. Useful if you need to execute view logic
   * after all currently queued view updates have completed.
   *
   * @param block that contains UI logic you want to execute.
   *     <p>Usage Example:
   *     <p>UIManagerModule uiManager = reactContext.getNativeModule(UIManagerModule.class);
   *     uiManager.addUIBlock(new UIBlock() { public void execute (NativeViewHierarchyManager nvhm)
   *     { View view = nvhm.resolveView(tag); // ...execute your code on View (e.g. snapshot the
   *     view) } });
   */
  public void addUIBlock(UIBlock block) {
    mUIImplementation.addUIBlock(block);
  }

  /**
   * Schedule a block to be executed on the UI thread. Useful if you need to execute view logic
   * before all currently queued view updates have completed.
   *
   * @param block that contains UI logic you want to execute.
   */
  public void prependUIBlock(UIBlock block) {
    mUIImplementation.prependUIBlock(block);
  }

  public void addUIManagerListener(UIManagerModuleListener listener) {
    mListeners.add(listener);
  }

  public void removeUIManagerListener(UIManagerModuleListener listener) {
    mListeners.remove(listener);
  }

  /**
   * Given a reactTag from a component, find its root node tag, if possible. Otherwise, this will
   * return 0. If the reactTag belongs to a root node, this will return the same reactTag.
   *
   * @param reactTag the component tag
   * @return the rootTag
   */
  public int resolveRootTagFromReactTag(int reactTag) {
    return ViewUtil.isRootTag(reactTag)
        ? reactTag
        : mUIImplementation.resolveRootTagFromReactTag(reactTag);
  }

  /** Dirties the node associated with the given react tag */
  public void invalidateNodeLayout(int tag) {
    ReactShadowNode node = mUIImplementation.resolveShadowNode(tag);
    if (node == null) {
      FLog.w(
          ReactConstants.TAG,
          "Warning : attempted to dirty a non-existent react shadow node. reactTag=" + tag);
      return;
    }
    node.dirty();
    mUIImplementation.dispatchViewUpdates(-1);
  }

  /**
   * Updates the styles of the {@link ReactShadowNode} based on the Measure specs received by
   * parameters.
   */
  public void updateRootLayoutSpecs(
      final int rootViewTag, final int widthMeasureSpec, final int heightMeasureSpec) {
    ReactApplicationContext reactApplicationContext = getReactApplicationContext();
    reactApplicationContext.runOnNativeModulesQueueThread(
        new GuardedRunnable(reactApplicationContext) {
          @Override
          public void runGuarded() {
            mUIImplementation.updateRootView(rootViewTag, widthMeasureSpec, heightMeasureSpec);
            mUIImplementation.dispatchViewUpdates(-1);
          }
        });
  }

  /** Listener that drops the CSSNode pool on low memory when the app is backgrounded. */
  private class MemoryTrimCallback implements ComponentCallbacks2 {

    @Override
    public void onTrimMemory(int level) {
      if (level >= TRIM_MEMORY_MODERATE) {
        YogaNodePool.get().clear();
      }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {}

    @Override
    public void onLowMemory() {}
  }

  public View resolveView(int tag) {
    UiThreadUtil.assertOnUiThread();
    return mUIImplementation.getUIViewOperationQueue().getNativeViewHierarchyManager().resolveView(tag);
  }
}
