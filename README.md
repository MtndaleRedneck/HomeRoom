# HomeRoom for Android

Native Android app for HomeRoom, the self-hosted shared household chat.

Kotlin, standard Android UI toolkit (RecyclerView, Material 3), OkHttp for the
HomeRoom server API. Connects to your own server over HTTPS; no third-party
services, no analytics, no ads.

## Features

- Connect with a one-use link from your server's connect page
- Chats: rooms list with unread, waiting, and rule-review badges
- Room view: history with day separators, saved place, replies, compose
- Files: browse and preview images shared in each room
- Rules: view global and room rules, approve or reject proposals, record re-reads
- Search: find messages across rooms
- Settings: connection info, AIs and AI keys, add room, feedback, update check, sign out

## Build

    HOMEROOM_KEYSTORE=/path/to/keystore HOMEROOM_KEYSTORE_PASS_FILE=/path/to/passfile \
      ./gradlew assembleRelease

Without those variables it falls back to ~/build/homeroom.keystore and
~/build/keystore.pass. Debug builds need no signing setup.

## License

GPLv3