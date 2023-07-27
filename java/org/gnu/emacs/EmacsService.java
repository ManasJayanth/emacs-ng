/* Communication module for Android terminals.  -*- c-file-style: "GNU" -*-

Copyright (C) 2023 Free Software Foundation, Inc.

This file is part of GNU Emacs.

GNU Emacs is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or (at
your option) any later version.

GNU Emacs is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with GNU Emacs.  If not, see <https://www.gnu.org/licenses/>.  */

package org.gnu.emacs;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import java.util.concurrent.atomic.AtomicInteger;

import android.database.Cursor;

import android.graphics.Matrix;
import android.graphics.Point;

import android.webkit.MimeTypeMap;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.ExtractedText;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.app.Service;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.UriPermission;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.content.pm.PackageManager;

import android.content.res.AssetManager;

import android.hardware.input.InputManager;

import android.net.Uri;

import android.os.BatteryManager;
import android.os.Build;
import android.os.Looper;
import android.os.IBinder;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.os.VibrationEffect;

import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import android.util.Log;
import android.util.DisplayMetrics;

import android.widget.Toast;

/* EmacsService is the service that starts the thread running Emacs
   and handles requests by that Emacs instance.  */

public final class EmacsService extends Service
{
  public static final String TAG = "EmacsService";

  /* The started Emacs service object.  */
  public static EmacsService SERVICE;

  /* If non-NULL, an extra argument to pass to
     `android_emacs_init'.  */
  public static String extraStartupArgument;

  /* The thread running Emacs C code.  */
  private EmacsThread thread;

  /* Handler used to run tasks on the main thread.  */
  private Handler handler;

  /* Content resolver used to access URIs.  */
  private ContentResolver resolver;

  /* Keep this in synch with androidgui.h.  */
  public static final int IC_MODE_NULL   = 0;
  public static final int IC_MODE_ACTION = 1;
  public static final int IC_MODE_TEXT   = 2;

  /* File access mode constants.  See `man 7 inode'.  */
  public static final int S_IRUSR = 0000400;
  public static final int S_IWUSR = 0000200;
  public static final int S_IFCHR = 0020000;
  public static final int S_IFDIR = 0040000;
  public static final int S_IFREG = 0100000;

  /* Display metrics used by font backends.  */
  public DisplayMetrics metrics;

  /* Flag that says whether or not to print verbose debugging
     information when responding to an input method.  */
  public static final boolean DEBUG_IC = false;

  /* Flag that says whether or not to stringently check that only the
     Emacs thread is performing drawing calls.  */
  private static final boolean DEBUG_THREADS = false;

  /* Atomic integer used for synchronization between
     icBeginSynchronous/icEndSynchronous and viewGetSelection.

     Value is 0 if no query is in progress, 1 if viewGetSelection is
     being called, and 2 if icBeginSynchronous was called.  */
  public static final AtomicInteger servicingQuery;

  static
  {
    servicingQuery = new AtomicInteger ();
  };

  /* Return the directory leading to the directory in which native
     library files are stored on behalf of CONTEXT.  */

  public static String
  getLibraryDirectory (Context context)
  {
    int apiLevel;

    apiLevel = Build.VERSION.SDK_INT;

    if (apiLevel >= Build.VERSION_CODES.GINGERBREAD)
      return context.getApplicationInfo ().nativeLibraryDir;

    return context.getApplicationInfo ().dataDir + "/lib";
  }

  @Override
  public int
  onStartCommand (Intent intent, int flags, int startId)
  {
    Notification notification;
    NotificationManager manager;
    NotificationChannel channel;
    String infoBlurb;
    Object tem;

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
      {
	tem = getSystemService (Context.NOTIFICATION_SERVICE);
	manager = (NotificationManager) tem;
	infoBlurb = ("This notification is displayed to keep Emacs"
		     + " running while it is in the background.  You"
		     + " may disable it if you want;"
		     + " see (emacs)Android Environment.");
	channel
	  = new NotificationChannel ("emacs", "Emacs persistent notification",
				     NotificationManager.IMPORTANCE_DEFAULT);
	manager.createNotificationChannel (channel);
	notification = (new Notification.Builder (this, "emacs")
			.setContentTitle ("Emacs")
			.setContentText (infoBlurb)
			.setSmallIcon (android.R.drawable.sym_def_app_icon)
			.build ());
	manager.notify (1, notification);
	startForeground (1, notification);
      }

    return START_NOT_STICKY;
  }

  @Override
  public IBinder
  onBind (Intent intent)
  {
    return null;
  }

  @SuppressWarnings ("deprecation")
  private String
  getApkFile ()
  {
    PackageManager manager;
    ApplicationInfo info;

    manager = getPackageManager ();

    try
      {
	if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
	  info = manager.getApplicationInfo ("org.gnu.emacs", 0);
	else
	  info = manager.getApplicationInfo ("org.gnu.emacs",
					     ApplicationInfoFlags.of (0));

	/* Return an empty string upon failure.  */

	if (info.sourceDir != null)
	  return info.sourceDir;

	return "";
      }
    catch (Exception e)
      {
	return "";
      }
  }

  @Override
  public void
  onCreate ()
  {
    final AssetManager manager;
    Context app_context;
    final String filesDir, libDir, cacheDir, classPath;
    final double pixelDensityX;
    final double pixelDensityY;
    final double scaledDensity;
    double tempScaledDensity;

    SERVICE = this;
    handler = new Handler (Looper.getMainLooper ());
    manager = getAssets ();
    app_context = getApplicationContext ();
    metrics = getResources ().getDisplayMetrics ();
    pixelDensityX = metrics.xdpi;
    pixelDensityY = metrics.ydpi;
    tempScaledDensity = ((metrics.scaledDensity
			  / metrics.density)
			 * pixelDensityX);
    resolver = getContentResolver ();

    /* If the density used to compute the text size is lesser than
       160, there's likely a bug with display density computation.
       Reset it to 160 in that case.

       Note that Android uses 160 ``dpi'' as the density where 1 point
       corresponds to 1 pixel, not 72 or 96 as used elsewhere.  This
       difference is codified in PT_PER_INCH defined in font.h.  */

    if (tempScaledDensity < 160)
      tempScaledDensity = 160;

    /* scaledDensity is const as required to refer to it from within
       the nested function below.  */
    scaledDensity = tempScaledDensity;

    try
      {
	/* Configure Emacs with the asset manager and other necessary
	   parameters.  */
	filesDir = app_context.getFilesDir ().getCanonicalPath ();
	libDir = getLibraryDirectory (this);
	cacheDir = app_context.getCacheDir ().getCanonicalPath ();

	/* Now provide this application's apk file, so a recursive
	   invocation of app_process (through android-emacs) can
	   find EmacsNoninteractive.  */
	classPath = getApkFile ();

	Log.d (TAG, "Initializing Emacs, where filesDir = " + filesDir
	       + ", libDir = " + libDir + ", and classPath = " + classPath
	       + "; fileToOpen = " + EmacsOpenActivity.fileToOpen
	       + "; display density: " + pixelDensityX + " by "
	       + pixelDensityY + " scaled to " + scaledDensity);

	/* Start the thread that runs Emacs.  */
	thread = new EmacsThread (this, new Runnable () {
	    @Override
	    public void
	    run ()
	    {
	      EmacsNative.setEmacsParams (manager, filesDir, libDir,
					  cacheDir, (float) pixelDensityX,
					  (float) pixelDensityY,
					  (float) scaledDensity,
					  classPath, EmacsService.this);
	    }
	  }, extraStartupArgument,
	  /* If any file needs to be opened, open it now.  */
	  EmacsOpenActivity.fileToOpen);
	thread.start ();
      }
    catch (IOException exception)
      {
	EmacsNative.emacsAbort ();
	return;
      }
  }



  /* Functions from here on must only be called from the Emacs
     thread.  */

  public void
  runOnUiThread (Runnable runnable)
  {
    handler.post (runnable);
  }

  public EmacsView
  getEmacsView (final EmacsWindow window, final int visibility,
		final boolean isFocusedByDefault)
  {
    Runnable runnable;
    final EmacsHolder<EmacsView> view;

    view = new EmacsHolder<EmacsView> ();

    runnable = new Runnable () {
	@Override
	public void
	run ()
	{
	  synchronized (this)
	    {
	      view.thing = new EmacsView (window);
	      view.thing.setVisibility (visibility);

	      /* The following function is only present on Android 26
		 or later.  */
	      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
		view.thing.setFocusedByDefault (isFocusedByDefault);

	      notify ();
	    }
	}
      };

    syncRunnable (runnable);
    return view.thing;
  }

  public void
  getLocationOnScreen (final EmacsView view, final int[] coordinates)
  {
    Runnable runnable;

    runnable = new Runnable () {
	public void
	run ()
	{
	  synchronized (this)
	    {
	      view.getLocationOnScreen (coordinates);
	      notify ();
	    }
	}
      };

    syncRunnable (runnable);
  }



  public static void
  checkEmacsThread ()
  {
    if (DEBUG_THREADS)
      {
	if (Thread.currentThread () instanceof EmacsThread)
	  return;

	throw new RuntimeException ("Emacs thread function"
				    + " called from other thread!");
      }
  }

  /* These drawing functions must only be called from the Emacs
     thread.  */

  public void
  fillRectangle (EmacsDrawable drawable, EmacsGC gc,
		 int x, int y, int width, int height)
  {
    checkEmacsThread ();
    EmacsFillRectangle.perform (drawable, gc, x, y,
				width, height);
  }

  public void
  fillPolygon (EmacsDrawable drawable, EmacsGC gc,
	       Point points[])
  {
    checkEmacsThread ();
    EmacsFillPolygon.perform (drawable, gc, points);
  }

  public void
  drawRectangle (EmacsDrawable drawable, EmacsGC gc,
		 int x, int y, int width, int height)
  {
    checkEmacsThread ();
    EmacsDrawRectangle.perform (drawable, gc, x, y,
				width, height);
  }

  public void
  drawLine (EmacsDrawable drawable, EmacsGC gc,
	    int x, int y, int x2, int y2)
  {
    checkEmacsThread ();
    EmacsDrawLine.perform (drawable, gc, x, y,
			   x2, y2);
  }

  public void
  drawPoint (EmacsDrawable drawable, EmacsGC gc,
	     int x, int y)
  {
    checkEmacsThread ();
    EmacsDrawPoint.perform (drawable, gc, x, y);
  }

  public void
  clearWindow (EmacsWindow window)
  {
    checkEmacsThread ();
    window.clearWindow ();
  }

  public void
  clearArea (EmacsWindow window, int x, int y, int width,
	     int height)
  {
    checkEmacsThread ();
    window.clearArea (x, y, width, height);
  }

  @SuppressWarnings ("deprecation")
  public void
  ringBell ()
  {
    Vibrator vibrator;
    VibrationEffect effect;
    VibratorManager vibratorManager;
    Object tem;

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
      {
	tem = getSystemService (Context.VIBRATOR_MANAGER_SERVICE);
	vibratorManager = (VibratorManager) tem;
        vibrator = vibratorManager.getDefaultVibrator ();
      }
    else
      vibrator
	= (Vibrator) getSystemService (Context.VIBRATOR_SERVICE);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
      {
	effect
	  = VibrationEffect.createOneShot (50,
					   VibrationEffect.DEFAULT_AMPLITUDE);
	vibrator.vibrate (effect);
      }
    else
      vibrator.vibrate (50);
  }

  public short[]
  queryTree (EmacsWindow window)
  {
    short[] array;
    List<EmacsWindow> windowList;
    int i;

    if (window == null)
      /* Just return all the windows without a parent.  */
      windowList = EmacsWindowAttachmentManager.MANAGER.copyWindows ();
    else
      windowList = window.children;

    array = new short[windowList.size () + 1];
    i = 1;

    array[0] = (window == null
		? 0 : (window.parent != null
		       ? window.parent.handle : 0));

    for (EmacsWindow treeWindow : windowList)
      array[i++] = treeWindow.handle;

    return array;
  }

  public int
  getScreenWidth (boolean mmWise)
  {
    DisplayMetrics metrics;

    metrics = getResources ().getDisplayMetrics ();

    if (!mmWise)
      return metrics.widthPixels;
    else
      return (int) ((metrics.widthPixels / metrics.xdpi) * 2540.0);
  }

  public int
  getScreenHeight (boolean mmWise)
  {
    DisplayMetrics metrics;

    metrics = getResources ().getDisplayMetrics ();

    if (!mmWise)
      return metrics.heightPixels;
    else
      return (int) ((metrics.heightPixels / metrics.ydpi) * 2540.0);
  }

  public boolean
  detectMouse ()
  {
    InputManager manager;
    InputDevice device;
    int[] ids;
    int i;

    if (Build.VERSION.SDK_INT
	/* Android 4.0 and earlier don't support mouse input events at
	   all.  */
	< Build.VERSION_CODES.JELLY_BEAN)
      return false;

    manager = (InputManager) getSystemService (Context.INPUT_SERVICE);
    ids = manager.getInputDeviceIds ();

    for (i = 0; i < ids.length; ++i)
      {
	device = manager.getInputDevice (ids[i]);

	if (device == null)
	  continue;

	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
	  {
	    if (device.supportsSource (InputDevice.SOURCE_MOUSE))
	      return true;
	  }
	else
	  {
	    /* `supportsSource' is only present on API level 21 and
	       later, but earlier versions provide a bit mask
	       containing each supported source.  */

	    if ((device.getSources () & InputDevice.SOURCE_MOUSE) != 0)
	      return true;
	  }
      }

    return false;
  }

  public String
  nameKeysym (int keysym)
  {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1)
      return KeyEvent.keyCodeToString (keysym);

    return String.valueOf (keysym);
  }



  /* Start the Emacs service if necessary.  On Android 26 and up,
     start Emacs as a foreground service with a notification, to avoid
     it being killed by the system.

     On older systems, simply start it as a normal background
     service.  */

  public static void
  startEmacsService (Context context)
  {
    if (EmacsService.SERVICE == null)
      {
	if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
	  /* Start the Emacs service now.  */
	  context.startService (new Intent (context,
					    EmacsService.class));
	else
	  /* Display the permanant notification and start Emacs as a
	     foreground service.  */
	  context.startForegroundService (new Intent (context,
						      EmacsService.class));
      }
  }

  /* Ask the system to open the specified URL in an application that
     understands how to open it.

     If SEND, tell the system to also open applications that can
     ``send'' the URL (through mail, for example), instead of only
     those that can view the URL.

     Value is NULL upon success, or a string describing the error
     upon failure.  */

  public String
  browseUrl (String url, boolean send)
  {
    Intent intent;
    Uri uri;

    try
      {
	/* Parse the URI.  */
	if (!send)
	  {
	    uri = Uri.parse (url);

	    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
	      {
		/* On Android 4.4 and later, check if URI is actually
		   a file name.  If so, rewrite it into a content
		   provider URI, so that it can be accessed by other
		   programs.  */

		if (uri.getScheme ().equals ("file")
		    && uri.getPath () != null)
		  uri
		    = DocumentsContract.buildDocumentUri ("org.gnu.emacs",
							  uri.getPath ());
	      }

	    Log.d (TAG, ("browseUri: browsing " + url
			 + " --> " + uri.getPath ()
			 + " --> " + uri));

	    intent = new Intent (Intent.ACTION_VIEW, uri);
	    intent.setFlags (Intent.FLAG_ACTIVITY_NEW_TASK
			     | Intent.FLAG_GRANT_READ_URI_PERMISSION);
	  }
	else
	  {
	    intent = new Intent (Intent.ACTION_SEND);
	    intent.setType ("text/plain");
	    intent.putExtra (Intent.EXTRA_SUBJECT, "Sharing link");
	    intent.putExtra (Intent.EXTRA_TEXT, url);

	    /* Display a list of programs able to send this URL.  */
	    intent = Intent.createChooser (intent, "Send");

	    /* Apparently flags need to be set after a choser is
	       created.  */
	    intent.addFlags (Intent.FLAG_ACTIVITY_NEW_TASK);
	  }

	startActivity (intent);
      }
    catch (Exception e)
      {
	return e.toString ();
      }

    return null;
  }

  /* Get a SDK 11 ClipboardManager.

     Android 4.0.x requires that this be called from the main
     thread.  */

  public ClipboardManager
  getClipboardManager ()
  {
    final EmacsHolder<ClipboardManager> manager;
    Runnable runnable;

    manager = new EmacsHolder<ClipboardManager> ();

    runnable = new Runnable () {
	public void
	run ()
	{
	  Object tem;

	  synchronized (this)
	    {
	      tem = getSystemService (Context.CLIPBOARD_SERVICE);
	      manager.thing = (ClipboardManager) tem;
	      notify ();
	    }
	}
      };

    syncRunnable (runnable);
    return manager.thing;
  }

  public void
  restartEmacs ()
  {
    Intent intent;

    intent = new Intent (this, EmacsActivity.class);
    intent.addFlags (Intent.FLAG_ACTIVITY_NEW_TASK
		     | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    startActivity (intent);
    System.exit (0);
  }

  /* Wait synchronously for the specified RUNNABLE to complete in the
     UI thread.  Must be called from the Emacs thread.  */

  public static void
  syncRunnable (Runnable runnable)
  {
    EmacsNative.beginSynchronous ();

    synchronized (runnable)
      {
	SERVICE.runOnUiThread (runnable);

	while (true)
	  {
	    try
	      {
		runnable.wait ();
		break;
	      }
	    catch (InterruptedException e)
	      {
		continue;
	      }
	  }
      }

    EmacsNative.endSynchronous ();
  }



  /* IMM functions such as `updateSelection' holds an internal lock
     that is also taken before `onCreateInputConnection' (in
     EmacsView.java) is called; when that then asks the UI thread for
     the current selection, a dead lock results.  To remedy this,
     reply to any synchronous queries now -- and prohibit more queries
     for the duration of `updateSelection' -- if EmacsView may have
     been asking for the value of the region.  */

  public static void
  icBeginSynchronous ()
  {
    /* Set servicingQuery to 2, so viewGetSelection knows it shouldn't
       proceed.  */

    if (servicingQuery.getAndSet (2) == 1)
      /* But if viewGetSelection is already in progress, answer it
	 first.  */
      EmacsNative.answerQuerySpin ();
  }

  public static void
  icEndSynchronous ()
  {
    if (servicingQuery.getAndSet (0) != 2)
      throw new RuntimeException ("incorrect value of `servicingQuery': "
				  + "likely 1");
  }

  public static int[]
  viewGetSelection (short window)
  {
    int[] selection;

    /* See if a query is already in progress from the other
       direction.  */
    if (!servicingQuery.compareAndSet (0, 1))
      return null;

    /* Now call the regular getSelection.  Note that this can't race
       with answerQuerySpin, as `android_servicing_query' can never be
       2 when icBeginSynchronous is called, so a query will always be
       started.  */
    selection = EmacsNative.getSelection (window);

    /* Finally, clear servicingQuery if its value is still 1.  If a
       query has started from the other side, it ought to be 2.  */

    servicingQuery.compareAndSet (1, 0);
    return selection;
  }



  public void
  updateIC (EmacsWindow window, int newSelectionStart,
	    int newSelectionEnd, int composingRegionStart,
	    int composingRegionEnd)
  {
    if (DEBUG_IC)
      Log.d (TAG, ("updateIC: " + window + " " + newSelectionStart
		   + " " + newSelectionEnd + " "
		   + composingRegionStart + " "
		   + composingRegionEnd));

    icBeginSynchronous ();
    window.view.imManager.updateSelection (window.view,
					   newSelectionStart,
					   newSelectionEnd,
					   composingRegionStart,
					   composingRegionEnd);
    icEndSynchronous ();
  }

  public void
  resetIC (EmacsWindow window, int icMode)
  {
    int oldMode;

    if (DEBUG_IC)
      Log.d (TAG, "resetIC: " + window + ", " + icMode);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
	&& (oldMode = window.view.getICMode ()) == icMode
	/* Don't do this if there is currently no input
	   connection.  */
	&& oldMode != IC_MODE_NULL)
      {
	if (DEBUG_IC)
	  Log.d (TAG, "resetIC: calling invalidateInput");

	/* Android 33 and later allow the IM reset to be optimized out
	   and replaced by a call to `invalidateInput', which is much
	   faster, as it does not involve resetting the input
	   connection.  */

	icBeginSynchronous ();
	window.view.imManager.invalidateInput (window.view);
	icEndSynchronous ();

	return;
      }

    window.view.setICMode (icMode);

    icBeginSynchronous ();
    window.view.icGeneration++;
    window.view.imManager.restartInput (window.view);
    icEndSynchronous ();
  }

  public void
  updateCursorAnchorInfo (EmacsWindow window, float x,
			  float y, float yBaseline,
			  float yBottom)
  {
    CursorAnchorInfo info;
    CursorAnchorInfo.Builder builder;
    Matrix matrix;
    int[] offsets;

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
      return;

    offsets = new int[2];
    builder = new CursorAnchorInfo.Builder ();
    matrix = new Matrix (window.view.getMatrix ());
    window.view.getLocationOnScreen (offsets);
    matrix.postTranslate (offsets[0], offsets[1]);
    builder.setMatrix (matrix);
    builder.setInsertionMarkerLocation (x, y, yBaseline, yBottom,
					0);
    info = builder.build ();



    if (DEBUG_IC)
      Log.d (TAG, ("updateCursorAnchorInfo: " + x + " " + y
		   + " " + yBaseline + "-" + yBottom));

    icBeginSynchronous ();
    window.view.imManager.updateCursorAnchorInfo (window.view, info);
    icEndSynchronous ();
  }



  /* Content provider functions.  */

  /* Open a content URI described by the bytes BYTES, a non-terminated
     string; make it writable if WRITABLE, and readable if READABLE.
     Truncate the file if TRUNCATE.

     Value is the resulting file descriptor or -1 upon failure.  */

  public int
  openContentUri (byte[] bytes, boolean writable, boolean readable,
		  boolean truncate)
  {
    String name, mode;
    ParcelFileDescriptor fd;
    int i;

    /* Figure out the file access mode.  */

    mode = "";

    if (readable)
      mode += "r";

    if (writable)
      mode += "w";

    if (truncate)
      mode += "t";

    /* Try to open an associated ParcelFileDescriptor.  */

    try
      {
	/* The usual file name encoding question rears its ugly head
	   again.  */

	name = new String (bytes, "UTF-8");
	fd = resolver.openFileDescriptor (Uri.parse (name), mode);

	/* Use detachFd on newer versions of Android or plain old
	   dup.  */

	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1)
	  {
	    i = fd.detachFd ();
	    fd.close ();

	    return i;
	  }
	else
	  {
	    i = EmacsNative.dup (fd.getFd ());
	    fd.close ();

	    return i;
	  }
      }
    catch (Exception exception)
      {
	return -1;
      }
  }

  public boolean
  checkContentUri (byte[] string, boolean readable, boolean writable)
  {
    String mode, name;
    ParcelFileDescriptor fd;

    /* Decode this into a URI.  */

    try
      {
	/* The usual file name encoding question rears its ugly head
	   again.  */
	name = new String (string, "UTF-8");
      }
    catch (UnsupportedEncodingException exception)
      {
	name = null;
	throw new RuntimeException (exception);
      }

    mode = "r";

    if (writable)
      mode += "w";

    try
      {
	fd = resolver.openFileDescriptor (Uri.parse (name), mode);
	fd.close ();

	return true;
      }
    catch (Exception exception)
      {
	/* Fall through.  */
      }

    return false;
  }

  /* Build a content file name for URI.

     Return a file name within the /contents/by-authority
     pseudo-directory that `android_get_content_name' can then
     transform back into an encoded URI.

     A content name consists of any number of unencoded path segments
     separated by `/' characters, possibly followed by a question mark
     and an encoded query string.  */

  public static String
  buildContentName (Uri uri)
  {
    StringBuilder builder;

    builder = new StringBuilder ("/content/by-authority/");
    builder.append (uri.getAuthority ());

    /* First, append each path segment.  */

    for (String segment : uri.getPathSegments ())
      {
	/* FIXME: what if segment contains a slash character? */
	builder.append ('/');
	builder.append (uri.encode (segment));
      }

    /* Now, append the query string if necessary.  */

    if (uri.getEncodedQuery () != null)
      builder.append ('?').append (uri.getEncodedQuery ());

    return builder.toString ();
  }



  private long[]
  queryBattery19 ()
  {
    IntentFilter filter;
    Intent battery;
    long capacity, chargeCounter, currentAvg, currentNow;
    long status, remaining, plugged, temp;

    filter = new IntentFilter (Intent.ACTION_BATTERY_CHANGED);
    battery = registerReceiver (null, filter);

    if (battery == null)
      return null;

    capacity = battery.getIntExtra (BatteryManager.EXTRA_LEVEL, 0);
    chargeCounter
      = (battery.getIntExtra (BatteryManager.EXTRA_SCALE, 0)
	 / battery.getIntExtra (BatteryManager.EXTRA_LEVEL, 100) * 100);
    currentAvg = 0;
    currentNow = 0;
    status = battery.getIntExtra (BatteryManager.EXTRA_STATUS, 0);
    remaining = -1;
    plugged = battery.getIntExtra (BatteryManager.EXTRA_PLUGGED, 0);
    temp = battery.getIntExtra (BatteryManager.EXTRA_TEMPERATURE, 0);

    return new long[] { capacity, chargeCounter, currentAvg,
			currentNow, remaining, status, plugged,
			temp, };
  }

  /* Return the status of the battery.  See struct
     android_battery_status for the order of the elements
     returned.

     Value may be null upon failure.  */

  public long[]
  queryBattery ()
  {
    Object tem;
    BatteryManager manager;
    long capacity, chargeCounter, currentAvg, currentNow;
    long status, remaining, plugged, temp;
    int prop;
    IntentFilter filter;
    Intent battery;

    /* Android 4.4 or earlier require applications to use a different
       API to query the battery status.  */

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
      return queryBattery19 ();

    tem = getSystemService (Context.BATTERY_SERVICE);
    manager = (BatteryManager) tem;
    remaining = -1;

    prop = BatteryManager.BATTERY_PROPERTY_CAPACITY;
    capacity = manager.getLongProperty (prop);
    prop = BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER;
    chargeCounter = manager.getLongProperty (prop);
    prop = BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE;
    currentAvg = manager.getLongProperty (prop);
    prop = BatteryManager.BATTERY_PROPERTY_CURRENT_NOW;
    currentNow = manager.getLongProperty (prop);

    /* Return the battery status.  N.B. that Android 7.1 and earlier
       only return ``charging'' or ``discharging''.  */

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
      status
	= manager.getIntProperty (BatteryManager.BATTERY_PROPERTY_STATUS);
    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
      status = (manager.isCharging ()
		? BatteryManager.BATTERY_STATUS_CHARGING
		: BatteryManager.BATTERY_STATUS_DISCHARGING);
    else
      status = (currentNow > 0
		? BatteryManager.BATTERY_STATUS_CHARGING
		: BatteryManager.BATTERY_STATUS_DISCHARGING);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
      remaining = manager.computeChargeTimeRemaining ();

    plugged = -1;
    temp = -1;

    /* Now obtain additional information from the battery manager.  */

    filter = new IntentFilter (Intent.ACTION_BATTERY_CHANGED);
    battery = registerReceiver (null, filter);

    if (battery != null)
      {
	plugged = battery.getIntExtra (BatteryManager.EXTRA_PLUGGED, 0);
	temp = battery.getIntExtra (BatteryManager.EXTRA_TEMPERATURE, 0);

	/* Make status more reliable.  */
	if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
	  status = battery.getIntExtra (BatteryManager.EXTRA_STATUS, 0);
      }

    return new long[] { capacity, chargeCounter, currentAvg,
			currentNow, remaining, status, plugged,
			temp, };
  }

  public void
  updateExtractedText (EmacsWindow window, ExtractedText text,
		       int token)
  {
    if (DEBUG_IC)
      Log.d (TAG, "updateExtractedText: @" + token + ", " + text);

    window.view.imManager.updateExtractedText (window.view,
					       token, text);
  }



  /* Document tree management functions.  These functions shouldn't be
     called before Android 5.0.

     TODO: a timeout, let alone quitting, has yet to be implemented
     for any of these functions.  */

  /* Return an array of each document authority providing at least one
     tree URI that Emacs holds the rights to persistently access.  */

  public String[]
  getDocumentAuthorities ()
  {
    List<UriPermission> permissions;
    HashSet<String> allProviders;
    Uri uri;

    permissions = resolver.getPersistedUriPermissions ();
    allProviders = new HashSet<String> ();

    for (UriPermission permission : permissions)
      {
	uri = permission.getUri ();

	if (DocumentsContract.isTreeUri (uri)
	    && permission.isReadPermission ())
	  allProviders.add (uri.getAuthority ());
      }

    return allProviders.toArray (new String[0]);
  }

  /* Start a file chooser activity to request access to a directory
     tree.

     Value is 1 if the activity couldn't be started for some reason,
     and 0 in any other case.  */

  public int
  requestDirectoryAccess ()
  {
    Runnable runnable;
    final EmacsHolder<Integer> rc;

    /* Return 1 if Android is too old to support this feature.  */

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
      return 1;

    rc = new EmacsHolder<Integer> ();
    rc.thing = Integer.valueOf (1);

    runnable = new Runnable () {
	@Override
	public void
	run ()
	{
	  EmacsActivity activity;
	  Intent intent;
	  int id;

	  synchronized (this)
	    {
	      /* Try to obtain an activity that will receive the
		 response from the file chooser dialog.  */

	      if (EmacsActivity.focusedActivities.isEmpty ())
		{
		  /* If focusedActivities is empty then this dialog
		     may have been displayed immediately after another
		     popup dialog was dismissed.  Try the
		     EmacsActivity to be focused.  */

		  activity = EmacsActivity.lastFocusedActivity;

		  if (activity == null)
		    {
		      /* Still no luck.  Return failure.  */
		      notify ();
		      return;
		    }
		}
	      else
		activity = EmacsActivity.focusedActivities.get (0);

	      /* Now create the intent.  */
	      intent = new Intent (Intent.ACTION_OPEN_DOCUMENT_TREE);

	      try
		{
		  id = EmacsActivity.ACCEPT_DOCUMENT_TREE;
		  activity.startActivityForResult (intent, id, null);
		  rc.thing = Integer.valueOf (0);
		}
	      catch (Exception e)
		{
		  e.printStackTrace ();
		}

	      notify ();
	    }
	}
      };

    syncRunnable (runnable);
    return rc.thing;
  }

  /* Return an array of each tree provided by the document PROVIDER
     that Emacs has permission to access.

     Value is an array if the provider really does exist, NULL
     otherwise.  */

  public String[]
  getDocumentTrees (byte provider[])
  {
    String providerName;
    List<String> treeList;
    List<UriPermission> permissions;
    Uri uri;

    try
      {
	providerName = new String (provider, "ASCII");
      }
    catch (UnsupportedEncodingException exception)
      {
	return null;
      }

    permissions = resolver.getPersistedUriPermissions ();
    treeList = new ArrayList<String> ();

    for (UriPermission permission : permissions)
      {
	uri = permission.getUri ();

	if (DocumentsContract.isTreeUri (uri)
	    && uri.getAuthority ().equals (providerName)
	    && permission.isReadPermission ())
	  /* Make sure the tree document ID is encoded.  */
	  treeList.add (Uri.encode (DocumentsContract.getTreeDocumentId (uri)));
      }

    return treeList.toArray (new String[0]);
  }

  /* Decode the specified STRING into a String object using the UTF-8
     format.  If an exception is thrown, return null.  */

  private String
  decodeFileName (byte[] string)
  {
    try
      {
	return new String (string, "UTF-8");
      }
    catch (Exception e) /* UnsupportedEncodingException, etc.  */
      {
	;;
      }

    return null;
  }

  /* Find the document ID of the file within TREE_URI designated by
     NAME.

     NAME is a ``file name'' comprised of the display names of
     individual files.  Each constituent component prior to the last
     must name a directory file within TREE_URI.

     Upon success, return 0 or 1 (contingent upon whether or not the
     last component within NAME is a directory) and place the document
     ID of the named file in ID_RETURN[0].

     If the designated file can't be located, but each component of
     NAME up to the last component can and is a directory, return -2
     and the ID of the last component located in ID_RETURN[0];

     If the designated file can't be located, return -1.  */

  private int
  documentIdFromName (String tree_uri, byte name[],
		      String[] id_return)
  {
    Uri uri, treeUri;
    String nameString, id, type;
    String[] components, projection;
    Cursor cursor;
    int column;

    projection = new String[] {
      Document.COLUMN_DISPLAY_NAME,
      Document.COLUMN_DOCUMENT_ID,
      Document.COLUMN_MIME_TYPE,
    };

    /* Parse the URI identifying the tree first.  */
    uri = Uri.parse (tree_uri);

    /* Next, decode NAME.  */
    nameString = decodeFileName (name);

    /* Now, split NAME into its individual components.  */
    components = nameString.split ("/");

    /* Set id and type to the value at the root of the tree.  */
    type = id = null;

    /* For each component... */

    for (String component : components)
      {
	/* Java split doesn't behave very much like strtok when it
	   comes to trailing and leading delimiters...  */
	if (component.isEmpty ())
	  continue;

	/* Create the tree URI for URI from ID if it exists, or the
	   root otherwise.  */

	if (id == null)
	  id = DocumentsContract.getTreeDocumentId (uri);

	treeUri
	  = DocumentsContract.buildChildDocumentsUriUsingTree (uri, id);

	/* Look for a file in this directory by the name of
	   component.  */

	try
	  {
	    cursor = resolver.query (treeUri, projection,
				     (Document.COLUMN_DISPLAY_NAME
				      + " = ?s"),
				     new String[] { component, }, null);
	  }
	catch (SecurityException exception)
	  {
	    /* A SecurityException can be thrown if Emacs doesn't have
	       access to treeUri.  */
	    return -1;
	  }
	catch (Exception exception)
	  {
	    exception.printStackTrace ();

	    /* Why is this? */
	    return -1;
	  }

	if (cursor == null)
	  return -1;

	while (true)
	  {
	    /* Even though the query selects for a specific display
	       name, some content providers nevertheless return every
	       file within the directory.  */

	    if (!cursor.moveToNext ())
	      {
		cursor.close ();

		/* If the last component considered is a
		   directory... */
		if ((type == null
		     || type.equals (Document.MIME_TYPE_DIR))
		    /* ... and type and id currently represent the
		       penultimate component.  */
		    && component == components[components.length  - 1])
		  {
		    /* The cursor is empty.  In this case, return -2
		       and the current document ID (belonging to the
		       previous component) in ID_RETURN.  */

		    id_return[0] = id;

		    /* But return -1 on the off chance that id is
		       null.  */

		    if (id == null)
		      return -1;

		    return -2;
		  }

		/* The last component found is not a directory, so
		   return -1.  */
		return -1;
	      }

	    /* So move CURSOR to a row with the right display
	       name.  */

	    column = cursor.getColumnIndex (Document.COLUMN_DISPLAY_NAME);

	    if (column < 0)
	      continue;

	    try
	      {
		nameString = cursor.getString (column);
	      }
	    catch (Exception exception)
	      {
		cursor.close ();
		return -1;
	      }

	    /* Break out of the loop only once a matching component is
	       found.  */

	    if (nameString.equals (component))
	      break;
	  }

	/* Look for a column by the name of COLUMN_DOCUMENT_ID.  */

	column = cursor.getColumnIndex (Document.COLUMN_DOCUMENT_ID);

	if (column < 0)
	  {
	    cursor.close ();
	    return -1;
	  }

	/* Now replace ID with the document ID.  */

	try
	  {
	    id = cursor.getString (column);
	  }
	catch (Exception exception)
	  {
	    cursor.close ();
	    return -1;
	  }

	/* If this is the last component, be sure to initialize the
	   document type.  */

	if (component == components[components.length - 1])
	  {
	    column
	      = cursor.getColumnIndex (Document.COLUMN_MIME_TYPE);

	    if (column < 0)
	      {
		cursor.close ();
		return -1;
	      }

	    try
	      {
		type = cursor.getString (column);
	      }
	    catch (Exception exception)
	      {
		cursor.close ();
		return -1;
	      }

	    /* Type may be NULL depending on how the Cursor returned
	       is implemented.  */

	    if (type == null)
	      {
		cursor.close ();
		return -1;
	      }
	  }

	/* Now close the cursor.  */
	cursor.close ();

	/* ID may have become NULL if the data is in an invalid
	   format.  */
	if (id == null)
	  return -1;
      }

    /* Here, id is either NULL (meaning the same as TREE_URI), and
       type is either NULL (in which case id should also be NULL) or
       the MIME type of the file.  */

    /* First return the ID.  */

    if (id == null)
      id_return[0] = DocumentsContract.getTreeDocumentId (uri);
    else
      id_return[0] = id;

    /* Next, return whether or not this is a directory.  */
    if (type == null || type.equals (Document.MIME_TYPE_DIR))
      return 1;

    return 0;
  }

  /* Return an encoded document URI representing a tree with the
     specified IDENTIFIER supplied by the authority AUTHORITY.

     Return null instead if Emacs does not have permanent access
     to the specified document tree recorded on disk.  */

  public String
  getTreeUri (String tree, String authority)
  {
    Uri uri, grantedUri;
    List<UriPermission> permissions;

    /* First, build the URI.  */
    tree = Uri.decode (tree);
    uri = DocumentsContract.buildTreeDocumentUri (authority, tree);

    /* Now, search for it within the list of persisted URI
       permissions.  */
    permissions = resolver.getPersistedUriPermissions ();

    for (UriPermission permission : permissions)
      {
	/* If the permission doesn't entitle Emacs to read access,
	   skip it.  */

	if (!permission.isReadPermission ())
	  continue;

        grantedUri = permission.getUri ();

	if (grantedUri.equals (uri))
	  return uri.toString ();
      }

    /* Emacs doesn't have permission to access this tree URI.  */
    return null;
  }

  /* Return file status for the document designated by the given
     DOCUMENTID and tree URI.  If DOCUMENTID is NULL, use the document
     ID in URI itself.

     Value is null upon failure, or an array of longs [MODE, SIZE,
     MTIM] upon success, where MODE contains the file type and access
     modes of the file as in `struct stat', SIZE is the size of the
     file in BYTES or -1 if not known, and MTIM is the time of the
     last modification to this file in milliseconds since 00:00,
     January 1st, 1970.  */

  public long[]
  statDocument (String uri, String documentId)
  {
    Uri uriObject;
    String[] projection;
    long[] stat;
    int index;
    long tem;
    String tem1;
    Cursor cursor;

    uriObject = Uri.parse (uri);

    if (documentId == null)
      documentId = DocumentsContract.getTreeDocumentId (uriObject);

    /* Create a document URI representing DOCUMENTID within URI's
       authority.  */

    uriObject
      = DocumentsContract.buildDocumentUriUsingTree (uriObject, documentId);

    /* Now stat this document.  */

    projection = new String[] {
      Document.COLUMN_FLAGS,
      Document.COLUMN_LAST_MODIFIED,
      Document.COLUMN_MIME_TYPE,
      Document.COLUMN_SIZE,
    };

    try
      {
	cursor = resolver.query (uriObject, projection, null,
				 null, null);
      }
    catch (SecurityException exception)
      {
	/* A SecurityException can be thrown if Emacs doesn't have
	   access to uriObject.  */
	return null;
      }
    catch (UnsupportedOperationException exception)
      {
	exception.printStackTrace ();

	/* Why is this? */
	return null;
      }

    if (cursor == null || !cursor.moveToFirst ())
      return null;

    /* Create the array of file status.  */
    stat = new long[3];

    try
      {
	index = cursor.getColumnIndex (Document.COLUMN_FLAGS);
	if (index < 0)
	  return null;

	tem = cursor.getInt (index);

	stat[0] |= S_IRUSR;
	if ((tem & Document.FLAG_SUPPORTS_WRITE) != 0)
	  stat[0] |= S_IWUSR;

	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
	    && (tem & Document.FLAG_VIRTUAL_DOCUMENT) != 0)
	  stat[0] |= S_IFCHR;

	index = cursor.getColumnIndex (Document.COLUMN_SIZE);
	if (index < 0)
	  return null;

	if (cursor.isNull (index))
	  stat[1] = -1; /* The size is unknown.  */
	else
	  stat[1] = cursor.getLong (index);

	index = cursor.getColumnIndex (Document.COLUMN_MIME_TYPE);
	if (index < 0)
	  return null;

	tem1 = cursor.getString (index);

	/* Check if this is a directory file.  */
	if (tem1.equals (Document.MIME_TYPE_DIR)
	    /* Files shouldn't be specials and directories at the same
	       time, but Android doesn't forbid document providers
	       from returning this information.  */
	    && (stat[0] & S_IFCHR) == 0)
	  /* Since FLAG_SUPPORTS_WRITE doesn't apply to directories,
	     just assume they're writable.  */
	  stat[0] |= S_IFDIR | S_IWUSR;

	/* If this file is neither a character special nor a
	   directory, indicate that it's a regular file.  */

	if ((stat[0] & (S_IFDIR | S_IFCHR)) == 0)
	  stat[0] |= S_IFREG;

	index = cursor.getColumnIndex (Document.COLUMN_LAST_MODIFIED);

	if (index >= 0 && !cursor.isNull (index))
	  {
	    /* Content providers are allowed to not provide mtime.  */
	    tem = cursor.getLong (index);
	    stat[2] = tem;
	  }
      }
    catch (Exception exception)
      {
	/* Whether or not type errors cause exceptions to be signaled
	   is defined ``by the implementation of Cursor'', whatever
	   that means.  */
	exception.printStackTrace ();
	return null;
      }

    return stat;
  }

  /* Find out whether Emacs has access to the document designated by
     the specified DOCUMENTID within the tree URI.  If DOCUMENTID is
     NULL, use the document ID in URI itself.

     If WRITABLE, also check that the file is writable, which is true
     if it is either a directory or its flags contains
     FLAG_SUPPORTS_WRITE.

     Value is 0 if the file is accessible, and one of the following if
     not:

       -1, if the file does not exist.
       -2, upon a security exception or if WRITABLE the file
           is not writable.
       -3, upon any other error.  */

  public int
  accessDocument (String uri, String documentId, boolean writable)
  {
    Uri uriObject;
    String[] projection;
    int tem, index;
    String tem1;
    Cursor cursor;

    uriObject = Uri.parse (uri);

    if (documentId == null)
      documentId = DocumentsContract.getTreeDocumentId (uriObject);

    /* Create a document URI representing DOCUMENTID within URI's
       authority.  */

    uriObject
      = DocumentsContract.buildDocumentUriUsingTree (uriObject, documentId);

    /* Now stat this document.  */

    projection = new String[] {
      Document.COLUMN_FLAGS,
      Document.COLUMN_MIME_TYPE,
    };

    try
      {
	cursor = resolver.query (uriObject, projection, null,
				 null, null);
      }
    catch (SecurityException exception)
      {
	/* A SecurityException can be thrown if Emacs doesn't have
	   access to uriObject.  */
	return -2;
      }
    catch (UnsupportedOperationException exception)
      {
	exception.printStackTrace ();

	/* Why is this? */
	return -3;
      }

    if (cursor == null || !cursor.moveToFirst ())
      return -1;

    if (!writable)
      return 0;

    try
      {
	index = cursor.getColumnIndex (Document.COLUMN_MIME_TYPE);
	if (index < 0)
	  return -3;

	/* Get the type of this file to check if it's a directory.  */
	tem1 = cursor.getString (index);

	/* Check if this is a directory file.  */
	if (tem1.equals (Document.MIME_TYPE_DIR))
	  {
	    /* If so, don't check for FLAG_SUPPORTS_WRITE.
	       Check for FLAG_DIR_SUPPORTS_CREATE instead.  */

	    if (!writable)
	      return 0;

	    index = cursor.getColumnIndex (Document.COLUMN_FLAGS);
	    if (index < 0)
	      return -3;

	    tem = cursor.getInt (index);
	    if ((tem & Document.FLAG_DIR_SUPPORTS_CREATE) == 0)
	      return -3;

	    return 0;
	  }

	index = cursor.getColumnIndex (Document.COLUMN_FLAGS);
	if (index < 0)
	  return -3;

	tem = cursor.getInt (index);
	if (writable && (tem & Document.FLAG_SUPPORTS_WRITE) == 0)
	  return -3;
      }
    catch (Exception exception)
      {
	/* Whether or not type errors cause exceptions to be signaled
	   is defined ``by the implementation of Cursor'', whatever
	   that means.  */
	exception.printStackTrace ();
	return -3;
      }

    return 0;
  }

  /* Open a cursor representing each entry within the directory
     designated by the specified DOCUMENTID within the tree URI.

     If DOCUMENTID is NULL, use the document ID within URI itself.
     Value is NULL upon failure.  */

  public Cursor
  openDocumentDirectory (String uri, String documentId)
  {
    Uri uriObject;
    Cursor cursor;
    String projection[];

    uriObject = Uri.parse (uri);

    /* If documentId is not set, use the document ID of the tree URI
       itself.  */

    if (documentId == null)
      documentId = DocumentsContract.getTreeDocumentId (uriObject);

    /* Build a URI representing each directory entry within
       DOCUMENTID.  */

    uriObject
      = DocumentsContract.buildChildDocumentsUriUsingTree (uriObject,
							   documentId);

    projection = new String [] {
      Document.COLUMN_DISPLAY_NAME,
      Document.COLUMN_MIME_TYPE,
    };

    try
      {
	cursor = resolver.query (uriObject, projection, null, null,
				 null);
      }
    catch (SecurityException exception)
      {
	/* A SecurityException can be thrown if Emacs doesn't have
	   access to uriObject.  */
	return null;
      }
    catch (UnsupportedOperationException exception)
      {
	exception.printStackTrace ();

	/* Why is this? */
	return null;
      }

    /* Return the cursor.  */
    return cursor;
  }

  /* Read a single directory entry from the specified CURSOR.  Return
     NULL if at the end of the directory stream, and a directory entry
     with `d_name' set to NULL if an error occurs.  */

  public EmacsDirectoryEntry
  readDirectoryEntry (Cursor cursor)
  {
    EmacsDirectoryEntry entry;
    int index;
    String name, type;

    entry = new EmacsDirectoryEntry ();

    while (true)
      {
	if (!cursor.moveToNext ())
	  return null;

	/* First, retrieve the display name.  */
	index = cursor.getColumnIndex (Document.COLUMN_DISPLAY_NAME);

	if (index < 0)
	  /* Return an invalid directory entry upon failure.  */
	  return entry;

	try
	  {
	    name = cursor.getString (index);
	  }
	catch (Exception exception)
	  {
	    return entry;
	  }

	/* Skip this entry if its name cannot be represented.  */

	if (name.equals ("..") || name.equals (".") || name.contains ("/"))
	  continue;

	/* Now, look for its type.  */

	index = cursor.getColumnIndex (Document.COLUMN_MIME_TYPE);

	if (index < 0)
	  /* Return an invalid directory entry upon failure.  */
	  return entry;

	try
	  {
	    type = cursor.getString (index);
	  }
	catch (Exception exception)
	  {
	    return entry;
	  }

	if (type.equals (Document.MIME_TYPE_DIR))
	  entry.d_type = 1;
	entry.d_name = name;
	return entry;
      }

    /* Not reached.  */
  }

  /* Open a file descriptor for a file document designated by
     DOCUMENTID within the document tree identified by URI.  If
     TRUNCATE and the document already exists, truncate its contents
     before returning.

     On Android 9.0 and earlier, always open the document in
     ``read-write'' mode; this instructs the document provider to
     return a seekable file that is stored on disk and returns correct
     file status.

     Under newer versions of Android, open the document in a
     non-writable mode if WRITE is false.  This is possible because
     these versions allow Emacs to explicitly request a seekable
     on-disk file.

     Value is NULL upon failure or a parcel file descriptor upon
     success.  Call `ParcelFileDescriptor.close' on this file
     descriptor instead of using the `close' system call.  */

  public ParcelFileDescriptor
  openDocument (String uri, String documentId, boolean write,
		boolean truncate)
  {
    Uri treeUri, documentUri;
    String mode;
    ParcelFileDescriptor fileDescriptor;

    treeUri = Uri.parse (uri);

    /* documentId must be set for this request, since it doesn't make
       sense to ``open'' the root of the directory tree.  */

    documentUri
      = DocumentsContract.buildDocumentUriUsingTree (treeUri, documentId);

    try
      {
	if (write || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
	  {
	    /* Select the mode used to open the file.  `rw' means open
	       a stat-able file, while `rwt' means that and to
	       truncate the file as well.  */

	    if (truncate)
	      mode = "rwt";
	    else
	      mode = "rw";

	    fileDescriptor
	      = resolver.openFileDescriptor (documentUri, mode,
					     null);
	  }
	else
	  {
	    /* Select the mode used to open the file.  `openFile'
	       below means always open a stat-able file.  */

	    if (truncate)
	      /* Invalid mode! */
	      return null;
	    else
	      mode = "r";

	    fileDescriptor = resolver.openFile (documentUri, mode, null);
	  }
      }
    catch (Exception exception)
      {
	return null;
      }

    return fileDescriptor;
  }

  /* Create a new document with the given display NAME within the
     directory identified by DOCUMENTID inside the document tree
     designated by URI.

     If DOCUMENTID is NULL, create the document inside the root of
     that tree.

     Return the document ID of the new file upon success, NULL
     otherwise.  */

  public String
  createDocument (String uri, String documentId, String name)
  {
    String mimeType, separator, mime, extension;
    int index;
    MimeTypeMap singleton;
    Uri directoryUri, docUri;

    /* Try to get the MIME type for this document.
       Default to ``application/octet-stream''.  */

    mimeType = "application/octet-stream";

    /* Abuse WebView stuff to get the file's MIME type.  */

    index = name.lastIndexOf ('.');

    if (index > 0)
      {
	singleton = MimeTypeMap.getSingleton ();
	extension = name.substring (index + 1);
	mime = singleton.getMimeTypeFromExtension (extension);

	if (mime != null)
	  mimeType = mime;
      }

    /* Now parse URI.  */
    directoryUri = Uri.parse (uri);

    if (documentId == null)
      documentId = DocumentsContract.getTreeDocumentId (directoryUri);

    /* And build a file URI referring to the directory.  */

    directoryUri
      = DocumentsContract.buildChildDocumentsUriUsingTree (directoryUri,
							   documentId);

    try
      {
	docUri = DocumentsContract.createDocument (resolver,
						   directoryUri,
						   mimeType, name);

	if (docUri == null)
	  return null;

	/* Return the ID of the new document.  */
	return DocumentsContract.getDocumentId (docUri);
      }
    catch (Exception exception)
      {
	exception.printStackTrace ();
      }

    return null;
  }
};
