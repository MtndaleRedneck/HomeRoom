# HomeRoom for Android

Native Android app for HomeRoom, the self-hosted shared household chat.

Kotlin, standard Android UI toolkit (RecyclerView, Material 3), OkHttp for the
HomeRoom server API. Connects to your own server over HTTPS. Outside requests: push notifications
through Firebase Cloud Messaging (Google) and an optional update check against
the project's GitHub releases page. Push messages carry a room ID, an event
ID and a message sequence number. The sequence number is the server's
running count of all messages, so Google can see roughly how many messages
the whole server handles and when, not just the ones sent to this phone. No
message text, room names or sender names pass through Google. The app
fetches the content from your server on the device when a push arrives.
Google's FCM service also sees delivery timing and this device's Firebase
installation ID and push token.
There are no analytics, no tracking, and no ads.

## Features

- Connect with a one-use link from your server's connect page
- Stacked notifications: recent messages grouped per room, cleared when read
- Mute a room: long-press it in the room list
- Chats: rooms list with unread, waiting, and rule-review badges
- Room view: history with day separators, saved place, replies, compose
- Files: browse and preview images shared in each room
- Rules: view global and room rules, approve or reject proposals, record re-reads
- Search: header button to find messages across rooms
- Settings: connection info, update check, sign out

## Build

    HOMEROOM_KEYSTORE=/path/to/keystore HOMEROOM_KEYSTORE_PASS_FILE=/path/to/passfile \
      ./gradlew assembleRelease

Without those variables it falls back to ~/build/homeroom.keystore and
~/build/keystore.pass. Debug builds need no signing setup.

## Visual regression checks

Run `gradle testDebugUnitTest`. Robolectric runs the native Android view
measurement/drawing checks and writes Chats and Room PNGs to
`app/build/native-previews/`. These use sample data, not a live server.
A real phone must still check enrollment, API requests, sending, push delivery,
launcher masking and system launch behavior. Android 12+ controls the system
starting window; the app uses an empty matching background, no branded splash,
and removes its exit animation immediately.

## License

GPLv3
