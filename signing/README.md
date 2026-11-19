# Signing key

Android installs an update over an existing app only if both are signed with the same key. So
every build of this app, debug and release, is signed with one personal key: a rebuilt APK then
installs over the app on your phone and keeps the watchlist and settings.

The key and its password exist only on your machine, in two files git ignores:

- `signing/personal.jks`: the key
- `signing/keystore.properties`: where the key is (`storeFile`, relative to this folder or
  absolute) and its password

Create them once with:

    signing/generate-key.sh

**Back up both files together**, for example in a password manager. To build on another computer,
copy them there rather than generating new ones. If they are lost, a new key can be generated,
but the app installed with the old one then has to be uninstalled before a new build installs,
and uninstalling deletes its locally stored watchlist.

Without these files, debug builds are signed with Android's standard debug key and release builds
come out unsigned.

## The old key

Earlier commits of this repository contain a key (`signing/pricewatch.jks`) and its password.
Since anyone can read them, that key is no longer used. An app installed from a build signed with
it has to be uninstalled once before builds signed with your own key install.
