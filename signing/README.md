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

## Signing on GitHub Actions

`.github/workflows/build.yml` builds the APK on GitHub. It can sign it with your key only if you
put the key in the repository's secrets, so that it leaves your machine. Before you do, consider:

- Anyone who can push to this repository can write a workflow that reads the secrets. Today that
  is only you.
- Pull requests never get the key: the workflow skips that step for them. GitHub also withholds
  secrets from pull requests opened from forks.
- The repository is public, so any signed-in GitHub user can download the signed APK from a run.
  The APK contains no secrets (your Twelve Data key lives on the phone), but anyone could install
  your build.

To add the key, open the repository's Settings, then Secrets and variables, then Actions, and
create two secrets:

- `SIGNING_KEYSTORE_BASE64`: the output of `base64 -w0 signing/personal.jks`
- `SIGNING_KEYSTORE_PASSWORD`: the `storePassword` value in `signing/keystore.properties`

Or do it with `gh` (your token needs permission to write secrets):

    base64 -w0 signing/personal.jks | gh secret set SIGNING_KEYSTORE_BASE64
    sed -n 's/^storePassword=//p' signing/keystore.properties | tr -d '\n' | gh secret set SIGNING_KEYSTORE_PASSWORD

To take the key off GitHub again, delete both secrets.

## The old key

Earlier commits of this repository contain a key (`signing/pricewatch.jks`) and its password.
Since anyone can read them, that key is no longer used. An app installed from a build signed with
it has to be uninstalled once before builds signed with your own key install.
