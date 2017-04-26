/*
 * Copyright (C) 2017 Oasis Feng. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.oasisfeng.condom;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.support.annotation.CheckResult;
import android.support.annotation.Nullable;
import android.support.annotation.RestrictTo;
import android.support.annotation.VisibleForTesting;
import android.util.EventLog;
import android.util.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND;
import static android.content.Context.ACTIVITY_SERVICE;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.HONEYCOMB_MR1;
import static android.os.Build.VERSION_CODES.LOLLIPOP_MR1;
import static android.os.Build.VERSION_CODES.N;

/**
 * The shared functionality for condom wrappers.
 *
 * Created by Oasis on 2017/4/21.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
class CondomCore {

	boolean shouldAllowProvider(final ProviderInfo provider) {
		return mBase.getPackageName().equals(provider.packageName) || ! shouldBlockRequestTarget(OutboundType.CONTENT, provider.packageName)
				&& (SDK_INT < HONEYCOMB_MR1 || ! mExcludeStoppedPackages || (provider.applicationInfo.flags & ApplicationInfo.FLAG_STOPPED) == 0);
	}

	interface WrappedValueProcedure<R> extends WrappedValueProcedureThrows<R, RuntimeException> {}

	interface WrappedValueProcedureThrows<R, T extends Throwable> { R proceed(Intent intent) throws T; }

	static abstract class WrappedProcedure implements WrappedValueProcedure<Void> {
		abstract void run(Intent intent);
		@Override public Void proceed(final Intent intent) { run(intent); return null; }
	}

	@SuppressLint("CheckResult") void proceedBroadcast(final Intent intent, final CondomCore.WrappedProcedure procedure) {
		proceed(OutboundType.BROADCAST, intent, null, procedure);
	}

	@CheckResult <R, T extends Throwable> R proceed(final OutboundType type, final Intent intent, final @Nullable R negative_value,
													final CondomCore.WrappedValueProcedureThrows<R, T> procedure) throws T {
		final String target_pkg = getTargetPackage(intent);
		if (target_pkg != null) {
			if (mBase.getPackageName().equals(target_pkg)) return procedure.proceed(intent);	// Self-targeting request is allowed unconditionally

			if (shouldBlockRequestTarget(type, target_pkg)) return negative_value;
		}
		final int original_flags = adjustIntentFlags(type, intent);
		try {
			return procedure.proceed(intent);
		} finally {
			intent.setFlags(original_flags);
		}
	}

	@CheckResult <T extends Throwable> List<ResolveInfo> proceedQuery(
			final OutboundType type, final Intent intent, final CondomCore.WrappedValueProcedureThrows<List<ResolveInfo>, T> procedure) throws T {
		return proceed(type, intent, Collections.<ResolveInfo>emptyList(), new CondomCore.WrappedValueProcedureThrows<List<ResolveInfo>, T>() { @Override public List<ResolveInfo> proceed(final Intent intent) throws T {
			final List<ResolveInfo> candidates = procedure.proceed(intent);

			if (mOutboundJudge != null && getTargetPackage(intent) == null) {	// Package-targeted intent is already filtered by OutboundJudge in proceed().
				final Iterator<ResolveInfo> iterator = candidates.iterator();
				while (iterator.hasNext()) {
					final ResolveInfo candidate = iterator.next();
					final String pkg = type == OutboundType.QUERY_SERVICES ? candidate.serviceInfo.packageName
							: (type == OutboundType.QUERY_RECEIVERS ? candidate.activityInfo.packageName : null);
					if (pkg != null && shouldBlockRequestTarget(type, pkg))
						iterator.remove();		// TODO: Not safe to assume the list returned from PackageManager is modifiable.
				}
			}
			return candidates;
		}});
	}

	static String getTargetPackage(final Intent intent) {
		final ComponentName component = intent.getComponent();
		return component != null ? component.getPackageName() : intent.getPackage();
	}

	private boolean shouldBlockRequestTarget(final OutboundType type, final String target_pkg) {
		return mOutboundJudge != null && ! mOutboundJudge.shouldAllow(type, target_pkg) && ! mDryRun;
	}

	private int adjustIntentFlags(final OutboundType type, final Intent intent) {
		final int original_flags = intent.getFlags();
		if (mDryRun) return original_flags;
		if (mExcludeBackgroundReceivers && (type == OutboundType.BROADCAST || type == OutboundType.QUERY_RECEIVERS))
			intent.addFlags(SDK_INT >= N ? FLAG_RECEIVER_EXCLUDE_BACKGROUND : Intent.FLAG_RECEIVER_REGISTERED_ONLY);
		if (SDK_INT >= HONEYCOMB_MR1 && mExcludeStoppedPackages)
			intent.setFlags((intent.getFlags() & ~ Intent.FLAG_INCLUDE_STOPPED_PACKAGES) | Intent.FLAG_EXCLUDE_STOPPED_PACKAGES);
		return original_flags;
	}

	@Nullable ResolveInfo getFirstNonBackground(final Intent intent, final @Nullable List<ResolveInfo> candidates, final String tag) {
		if (candidates == null || candidates.isEmpty()) return null;

		final int my_uid = Process.myUid();
		BackgroundUidFilter bg_uid_filter = null;
		for (final ResolveInfo candidate : candidates) {
			final ApplicationInfo app_info = candidate.serviceInfo.applicationInfo;
			final int uid = app_info.uid;
			if (uid == my_uid) return candidate;		// Self UID is always allowed
			if (bg_uid_filter == null) bg_uid_filter = new BackgroundUidFilter();
			if (bg_uid_filter.isUidNotBackground(uid)) return candidate;
			log(tag, CondomEvent.FILTER_BG_SERVICE, app_info.packageName, intent.toString());
		}
		return null;
	}

	enum CondomEvent { CONCERN, BIND_PASS, START_PASS, FILTER_BG_SERVICE }

	void log(final String tag, final CondomEvent event, final Object... args) {
		final Object[] event_args = new Object[2 + args.length];
		event_args[0] = mBase.getPackageName(); event_args[1] = tag;
		System.arraycopy(args, 0, event_args, 2, args.length);
		EventLog.writeEvent(EVENT_TAG + event.ordinal(), event_args);
		if (DEBUG) Log.d(tag, event.name() + " " + Arrays.toString(args));
	}

	void logConcern(final String tag, final String label) {
		EventLog.writeEvent(EVENT_TAG + CondomEvent.CONCERN.ordinal(), label, getCaller());
		if (DEBUG) Log.w(tag, label + " is invoked", new Throwable());
	}

	private static String getCaller() {
		final StackTraceElement[] stack = Thread.currentThread().getStackTrace();
		if (stack.length <= 5) return "<bottom>";
		final StackTraceElement caller = stack[5];
		return caller.getClassName() + "." + caller.getMethodName() + ":" + caller.getLineNumber();
	}

	static String buildTag(final String default_tag, final String prefix, final @Nullable String tag) {
		return tag == null || tag.isEmpty() ? default_tag : limitTagLength(prefix + tag);
	}

	private static String limitTagLength(final String tag) {	// Logging tag can be at most 23 characters.
		return tag.length() > 23 ? tag.substring(0, 22) + "…" : tag;
	}

	CondomCore(final Context base) {
		mBase = base;
		DEBUG = (base.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
	}

	final Context mBase;
	final boolean DEBUG;

	boolean mDryRun;
	@Nullable OutboundJudge mOutboundJudge;
	boolean mExcludeStoppedPackages = true;
	boolean mExcludeBackgroundReceivers = true;
	boolean mExcludeBackgroundServices = true;

	private static final int EVENT_TAG = "Condom".hashCode();

	/**
	 * If set, the broadcast will never go to manifest receivers in background (cached
	 * or not running) apps, regardless of whether that would be done by default.  By
	 * default they will receive broadcasts if the broadcast has specified an
	 * explicit component or package name.
	 *
	 * @since API level 24 (Android N)
	 */
	@VisibleForTesting static final int FLAG_RECEIVER_EXCLUDE_BACKGROUND = 0x00800000;

	class BackgroundUidFilter {

		boolean isUidNotBackground(final int uid) {
			if (running_processes != null) {
				for (final ActivityManager.RunningAppProcessInfo running_process : running_processes)
					if (running_process.pid != 0 && running_process.importance < IMPORTANCE_BACKGROUND && running_process.uid == uid)
						return true;	// Same UID does not guarantee same process. This is spared intentionally.
			} else if (running_services != null) {
				for (final ActivityManager.RunningServiceInfo running_service : running_services)
					if (running_service.pid != 0 && running_service.uid == uid)	// Same UID does not guarantee same process. This is spared intentionally.
						return true;	// Only running process is qualified, although getRunningServices() may not include all running app processes.
			}
			return false;
		}

		BackgroundUidFilter() {
			if (SDK_INT >= LOLLIPOP_MR1) {		// getRunningAppProcesses() is limited on Android 5.1+.
				running_services = ((ActivityManager) mBase.getSystemService(ACTIVITY_SERVICE)).getRunningServices(32);	// Too many services are never healthy, thus ignored intentionally.
				running_processes = null;
			} else {
				running_services = null;
				running_processes = ((ActivityManager) mBase.getSystemService(ACTIVITY_SERVICE)).getRunningAppProcesses();
			}
		}

		private final @Nullable List<ActivityManager.RunningServiceInfo> running_services;
		private final @Nullable List<ActivityManager.RunningAppProcessInfo> running_processes;
	}
}
