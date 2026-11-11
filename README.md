<p>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset=".github/lockup-dark.svg">
    <img src=".github/lockup.svg" alt="Feddy" width="160">
  </picture>
</p>

# Feddy Android SDK

In-app support for your users: they write in, you reply from the dashboard,
the reply reaches them in your app. Kotlin, Android 6.0 (API 23) and later,
no third-party dependencies.

[Website](https://feddy.app) · [Documentation](https://feddy.app/docs) · [Dashboard](https://dash.feddy.app)

## Installation

In your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("app.feddy:feddy:0.1.1")
}
```

It depends only on Google's own AndroidX libraries (Jetpack Compose,
Activity) and kotlinx-coroutines, at versions that build in an app compiling
against API 35 or later. If your app already uses newer versions, Gradle
picks yours. There is no OkHttp, Retrofit or image library inside to clash
with your own.

No permissions beyond `INTERNET`, which the SDK's manifest declares.

## Quick start

Copy your project's ID from the [Feddy dashboard](https://dash.feddy.app)
(**Settings → Project ID**). New to Feddy?
[Create a project](https://feddy.app/docs/getting-started/create-a-project)
first. It ships inside your app, so it is public by design — checking it
into source control is fine.

Configure once, as early as you can:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Feddy.configure(this, projectId = "fd_...")
    }
}
```

Then open the support screen from anywhere you have a `Context`:

```kotlin
Feddy.present(context)                  // home: greeting, new message, recent conversations
Feddy.presentNewConversation(context)   // straight into the compose form
```

The screens run in an activity of the SDK's own, so this works the same from
Jetpack Compose, from Views, and from a single-activity Flutter or React
Native app. Your theme, your activity class and your navigation are left
alone. Java callers use the same methods: `Feddy.present(this)`.

## Identify your user (optional)

```kotlin
Feddy.identify(
    userId = "u_123",
    email = "user@example.com",
    attributes = mapOf(
        "plan" to "pro",
        "is_member" to true,
        "trial_ends_at" to Date(),
        "credits_left" to 42,
    ),
)
```

Attribute values may be `String`, `Boolean`, numbers, or `java.util.Date`;
anything else is dropped. Calls take effect in the order you make them, and
each replaces the whole attribute set, so call again whenever a value
changes.

Anonymous users work fine without `identify` — their identity is a
generated id kept in the SDK's own preferences file. Android Auto Backup
(on by default) restores it after a reinstall, along with the user's
history.

## Unread badge

In Compose, drop the marker into your own "Help" row; it draws nothing
while nothing is unread:

```kotlin
Row(verticalAlignment = Alignment.CenterVertically) {
    Text("Help & support")
    Spacer(Modifier.weight(1f))
    FeddyUnreadDot()                    // or FeddyUnreadDot(showsCount = true)
}
```

Or read the count yourself:

```kotlin
val unread by Feddy.unreadCountFlow.collectAsState()
```

From Views or Java:

```kotlin
val listener = FeddyUnreadListener { count -> badge.isVisible = count > 0 }
Feddy.addUnreadCountListener(listener)     // remove it in onStop / onDestroy
Feddy.unreadCount(listener)                // one-off fetch
```

The SDK refreshes the count when your app comes to the foreground and every
30 seconds while it is there. Call `Feddy.refresh()` after anything that
should show a fresh count at once.

## Reply notifications

The SDK does not use push, so there is no Firebase project or server key to
configure. **The SDK posts no notifications and asks for no notification
permission.** A reply reaches the user two ways:

- **In the app** — when the user comes back with a reply they have not
  seen, a card slides in at the top of the screen; tapping it opens the
  conversation, swiping it up dismisses it. Each reply shows at most once,
  and only replies from the last 14 days. It is drawn by the SDK over your
  current screen, not posted as a notification. Set
  `Feddy.replyPopupsEnabled = false` around screens that must not be
  interrupted (a paywall, a game level) and back to `true` afterwards.
- **Away from the app** — if the user left an email address, the reply is
  emailed to them in full. The support screen asks for one after the first
  message is sent, and again when a teammate replies. Someone who skips is
  not asked again; the envelope button in the conversation's top bar stays
  as the way back for as long as no address is on file. Replies are only
  ever emailed from a domain you verified in the dashboard (Settings → Email
  domain); until one is verified the SDK does not ask for an address at
  all.

## Auto-replies

If auto-reply is switched on in the dashboard, a matching answer appears in
the thread the moment the user sends a message, and keeps doing so until
someone on the team replies. The SDK asks "Was this helpful?" under the
latest auto-reply. Yes closes the conversation as resolved by the user —
they can still reply within 7 days to reopen it. No leaves it open and
flags the reply in the dashboard.

## Images

Users can attach up to five images to a message, chosen with the system
photo picker. It needs no storage or media permission (Android 13 and later
have it built in; older versions get it through Google Play services), so
there is nothing to add to your manifest. Each image is capped at 3000px on
its long edge and re-encoded as JPEG before upload, so a full-resolution
photo does not cost your user their data plan while a screenshot stays
readable.

## Localization

The screens follow the device language and ship in seventeen: English,
简体中文, 繁體中文, 日本語, 한국어, Deutsch, Français, Español, Português,
Русский, Italiano, Türkçe, Polski, Bahasa Indonesia, Tiếng Việt, ไทย and
العربية. Anything else falls back to English. In Arabic the screens lay out
right to left even if your app does not declare `android:supportsRtl`; the
reply popup, which sits in your app's window, follows your app's setting.
If your build keeps only some languages (`localeFilters` or
`resourceConfigurations`), the SDK's strings for the others are dropped
with your own.

The five topics every project starts with — Bug, Feature, Question,
Billing, Other — are translated by the SDK. Topics you add in the
dashboard, the reply-time promise and auto-reply answers are translated
server-side from what you filled in under Translations.

## Privacy

The SDK sends only what it needs to work: the messages and images the user
chooses to send, the email address if they give one, the user id and
attributes you pass to `identify`, and device context shown next to each
message in the dashboard (app version, Android version, device model,
language, time zone). Nothing is used for tracking and there are no
analytics. For the Google Play data safety form: user IDs, email address
(optional), messages, and photos (optional) are collected for app
functionality, are not shared, and are encrypted in transit.

## Wrappers

Flutter and React Native packages wrap this SDK rather than reimplementing
it, so their screens are these screens. Wrappers call
`Feddy.setWrapper(name, version)` right after `configure`; apps calling the
SDK directly never need it.

## Example app

The `example` module shows every call, including a plain non-Compose
activity. To point it at your own project, create
`example/feddy.local.properties`:

```properties
projectId=fd_...
```

then run `./gradlew :example:installDebug`.

## License

MIT. Icons are from Material Icons (Apache License 2.0).
