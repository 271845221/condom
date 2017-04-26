[![Download](https://api.bintray.com/packages/oasisfeng/maven/condom/images/download.svg)](https://bintray.com/oasisfeng/maven/condom/_latestVersion)
[![Build Status](https://travis-ci.org/oasisfeng/condom.svg?branch=master)](https://travis-ci.org/oasisfeng/condom)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

# Project Condom

Project Condom is a thin library to wrap the naked `Context` in your Android project before passing it to the 3rd-party SDK.
It is designed to prevent the 3rd-party SDK from common unwanted behaviors which may harm the user experience of your app.

* Massive launch of processes in other apps (common in 3rd-party push SDKs), causing slow app starting and notable lagging
on low to middle-end devices. This behavior has "chain reaction" effects among apps with similar SDKs, greatly aggravating
the overall device performance.

## Quick Start

1. Add dependency to this library in build.gradle of your project module.

   ```
   compile 'com.oasisfeng.condom:library:1.0.2'
   ```

2. Migration the initialization code of 3rd-party SDK.

   Most 3rd-party SDKs require explicit initialization with a `Context` instance, something like:

   ```
   XxxClient.init(context, ...);
   ```

   Just change the `context` paramter to `CondomContext.wrap(context)`, like this:

   ```
   XxxClient.init(CondomContext.wrap(context, "XxxSDK"), ...);
   ```

That's it! Enjoy the protection.

---------------

# 保险套项目

『保险套』是一个超轻超薄的Android工具库，将它套在Android应用工程里裸露的`Context`上，再传入第三方SDK（通常是其初始化方法），即可防止三方SDK
中常见的损害用户体验的行为：

* 在后台启动大量其它应用的进程（在三方推送SDK中较为常见），导致应用启动非常缓慢，启动后一段时间内出现严重的卡顿（在中低端机型上尤其明显）。
这是由于在这些SDK初始化阶段启动的其它应用中往往也存在三方SDK的类似行为，造成了进程启动的『链式反应』，在短时间内消耗大量的CPU、文件IO及
内存资源，使得当前应用所能得到的资源被大量挤占（甚至耗尽）。

**注意：此项目通常并不适用于核心功能强依赖特定外部应用或组件的SDK（如Facebook SDK、Google Play services SDK）。** 如果希望在使用此类SDK时避免后台唤醒依赖的应用，仅在特定条件下（如用户主动作出相关操作时）调用SDK所依赖的应用，则可以使用本项目，并通过`CondomContext.setOutboundJudge()`自主控制何时放行。

## 快速开始

1. 首先在工程中添加对此项目的依赖项。

   对于Gradle工程，直接在模块的依赖项清单中添加下面这一行：

   ```
   compile 'com.oasisfeng.condom:library:1.0.2'
   ```

   对于非Gradle工程，请[下载AAR文件](http://jcenter.bintray.com/com/oasisfeng/condom/library/)放进项目模块本地的 `libs` 路径中，并在工程的ProGuard配置文件中增加以下两行：（Gradle工程和不使用ProGuard的工程不需要这一步）

   ```
   -dontwarn android.content.IContentProvider
   -dontwarn android.content.ContentResolver
   ```

2. 略微修改三方SDK的初始化代码。

   常见的三方SDK需要应用在启动阶段调用其初始化方法，一般包含`Context`参数，例如：

   ```
   XxxClient.init(context, ...);
   ```

   只需将其修改为：

   ```
   XxxClient.init(CondomContext.wrap(context, "XxxSDK"), ...);
   ```

   其中参数`tag`（上例中的"XxxSDK"）为开发者根据需要指定的用于区分多个不同`CondomContext`实例的标识，将出现在日志的TAG后缀。如果只有一个`CondomContext`实例，或者不需要区分，则传入null亦可。

就这样简单的一行修改，三方SDK就无法再使用这个套上了保险套的`Context`去唤醒当前并没有进程在运行的其它app。
（已有进程在运行中的app仍可以被关联调用，因为不存在大量进程连锁创建的巨大资源开销，因此是被允许的。这也是Android O开始实施的限制原则）

## 工作原理

`CondomContext`是一个加入了特定API拦截和调整机制的`ContextWrapper`，这些调整和拦截包括：（均可单独开启或关闭）

* 开发者可主动设置一个```OutboundJudge```回调，方便根据需求定制拦截策略。
* 避免通过此Context发出的广播启动其它应用的进程。在Android N以上，通过为非应用内广播的```Intent```添加```FLAG_RECEIVER_EXCLUDE_BACKGROUND```标志达成；在低版本Android系统中，通过添加```FLAG_RECEIVER_REGISTERED_ONLY```达到类似的效果。
* 避免通过此Context发出的广播或请求的服务启动已被用户强行停止的应用。通过为发往应用之外的广播或服务请求```Intent```添加```FLAG_EXCLUDE_STOPPED_PACKAGES```标识达成。
