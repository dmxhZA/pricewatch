# Signing key

`pricewatch.jks` signs every build of this app (debug and release). Its password is in
`app/build.gradle.kts`.

Android installs an update over an existing app only if both are signed with the same key.
Keeping this one key with the project means any rebuild, on any machine, can be installed on
top of the app already on your phone, with the watchlist and settings preserved.

Keep this file together with the project. If it is lost, a new build can only be installed
after uninstalling the old app, which deletes its locally stored watchlist.

It is a personal sideloading key with a public password. Do not use it to publish the app.
